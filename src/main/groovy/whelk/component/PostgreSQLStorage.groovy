package whelk.component

import groovy.util.logging.Slf4j as Log

import java.sql.*
import org.apache.commons.dbcp2.*

import whelk.*

@Log
class PostgreSQLStorage extends BasicComponent implements Storage {

    boolean versioning

    // Starta postgres: postgres -D /usr/local/var/postgres

    String mainTableName, versionsTableName

    // Database connectors
    URI dbUri

    // Connectionpool
    private BasicDataSource connectionPool


    // SQL statements
    protected String UPSERT_DOCUMENT, INSERT_DOCUMENT_VERSION, GET_DOCUMENT, GET_DOCUMENT_VERSION, GET_DOCUMENT_BY_ALTERNATE_ID

    PostgreSQLStorage(String componentId = null, Map settings) {
        this.contentTypes = settings.get('contentTypes', null)
        this.versioning = settings.get('versioning', false)
        this.dbUri = new URI(settings.get("databaseUrl"))
        id = componentId
    }

    void componentBootstrap(String str) {
        log.info("Bootstrapping ${this.id}")
        if (!this.mainTableName) {
            this.mainTableName = str+"_"+this.id
        }
        if (versioning) {
            this.versionsTableName = mainTableName+VERSION_STORAGE_SUFFIX
        }
        UPSERT_DOCUMENT = "WITH upsert AS (UPDATE $mainTableName SET data = ?, dataset = ?, modified = ?, entry = ?, meta = ? WHERE identifier = ? RETURNING *) " +
            "INSERT INTO $mainTableName (identifier, data, dataset, modified, entry, meta) SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT * FROM upsert)"


        INSERT_DOCUMENT_VERSION = "INSERT INTO $versionsTableName (identifier,data,checksum,modified,entry,meta) SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM $versionsTableName WHERE identifier = ? AND checksum = ?)"

        GET_DOCUMENT = "SELECT identifier,data,entry,meta FROM $mainTableName WHERE identifier = ?"
        GET_DOCUMENT_VERSION = "SELECT identifier,data,entry,meta FROM $versionsTableName WHERE identifier = ? AND version = ?"
        GET_DOCUMENT_BY_ALTERNATE_ID = "SELECT identifier,data,entry,meta FROM $mainTableName WHERE entry @> '{ \"alternateIdentifiers\": [?] }'"
    }

    @Override
    void onStart() {
        log.info("Connecting to postgres at $dbUri ... (${dbUri.scheme} / ${dbUri.host} / ${dbUri.port})")
        String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + dbUri.getPath();
        //String dbUrl = "jdbc:postgresql:whelk"
        connectionPool = new BasicDataSource();

        if (dbUri.getUserInfo() != null) {
            connectionPool.setUsername(dbUri.getUserInfo().split(":")[0]);
            connectionPool.setPassword(dbUri.getUserInfo().split(":")[1]);
        }
        connectionPool.setDriverClassName("org.postgresql.Driver");
        connectionPool.setUrl(dbUrl);
        connectionPool.setInitialSize(10);
        createTables()
    }

    void createTables() {
        Connection connection = connectionPool.getConnection()
        Statement stmt = connection.createStatement();
        stmt.executeUpdate("CREATE TABLE IF NOT EXISTS $mainTableName ("
            +"identifier varchar(200) primary key,"
            +"data bytea,"
            +"dataset varchar(20) not null,"
            +"modified timestamp,"
            +"entry jsonb,"
            +"meta jsonb"
            +")");
        if (versioning) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS $versionsTableName ("
                +"id serial,"
                +"identifier varchar(200) not null,"
                +"data bytea,"
                +"checksum char(32) not null,"
                +"modified timestamp,"
                +"entry jsonb,"
                +"meta jsonb,"
                +"UNIQUE (identifier, checksum)"
                +")");
            }
        stmt.close()
        connection.close()
    }

    @Override
    boolean eligibleForStoring(Document doc) {
        return true
    }

    @Override
    @groovy.transform.Synchronized
    boolean store(Document doc) {
        if (versioning) {
            saveVersion(doc)
        }
        log.debug("Saving document ${doc.identifier}")
        Connection connection = connectionPool.getConnection()
        PreparedStatement insert = connection.prepareStatement(UPSERT_DOCUMENT)
        try {
            insert.setBytes(1, doc.data)
            insert.setString(2, doc.dataset)
            insert.setTimestamp(3, new Timestamp(doc.modified))
            insert.setObject(4, doc.entryAsJson, java.sql.Types.OTHER)
            insert.setObject(5, doc.metaAsJson, java.sql.Types.OTHER)
            insert.setString(6, doc.identifier)
            insert.setString(7, doc.identifier)
            insert.setBytes(8, doc.data)
            insert.setString(9, doc.dataset)
            insert.setTimestamp(10, new Timestamp(doc.modified))
            insert.setObject(11, doc.entryAsJson, java.sql.Types.OTHER)
            insert.setObject(12, doc.metaAsJson, java.sql.Types.OTHER)
            insert.executeUpdate()
            return true
        } catch (Exception e) {
            log.error("Failed to save document: ${e.message}")
            throw e
        } finally {
            insert.close()
            connection.close()
        }
        return false
    }

    boolean saveVersion(Document doc) {
        log.debug("Saving a version of ${doc.identifier} with checksum ${doc.checksum}. Modified: ${doc.modified}")
        Connection connection = connectionPool.getConnection()
        PreparedStatement insvers = connection.prepareStatement(INSERT_DOCUMENT_VERSION)
        try {
            insvers.setString(1, doc.identifier)
            insvers.setBytes(2, doc.data)
            insvers.setString(3, doc.checksum)
            insvers.setTimestamp(4, new Timestamp(doc.modified))
            insvers.setObject(5, doc.entryAsJson, java.sql.Types.OTHER)
            insvers.setObject(6, doc.metaAsJson, java.sql.Types.OTHER)
            insvers.setString(7, doc.identifier)
            insvers.setString(8, doc.checksum)
            insvers.executeUpdate()
            return true
        } catch (Exception e) {
            log.error("Failed to save document version: ${e.message}")
            throw e
        } finally {
            insvers.close()
            connection.close()
        }
        return false
    }

    @Override
    void bulkStore(final List docs) {
        if (!docs || docs.isEmpty()) {
            return
        }
        Connection connection = connectionPool.getConnection()
        PreparedStatement batch = connection.prepareStatement(UPSERT_DOCUMENT)
        try {
            docs.each { doc ->
                batch.setBytes(1, doc.data)
                batch.setString(2, doc.dataset)
                batch.setTimestamp(3, new Timestamp(doc.modified))
                batch.setObject(4, doc.entryAsJson, java.sql.Types.OTHER)
                batch.setObject(5, doc.metaAsJson, java.sql.Types.OTHER)
                batch.setString(6, doc.identifier)
                batch.setString(7, doc.identifier)
                batch.setBytes(8, doc.data)
                batch.setString(9, doc.dataset)
                batch.setTimestamp(10, new Timestamp(doc.modified))
                batch.setObject(11, doc.entryAsJson, java.sql.Types.OTHER)
                batch.setObject(12, doc.metaAsJson, java.sql.Types.OTHER)
                batch.addBatch()
            }
            batch.executeBatch()
        } catch (Exception e) {
            log.error("Failed to save batch: ${e.message}")
            throw e
        } finally {
            batch.close()
            connection.close()
        }
    }

    Document load(String id) {
        return load(id, null)
    }


    // TODO: Idea for version: If numeric version, list all versions ordered by id, and count until desired version number is found. (consider feasability of this)
    @Override
    Document load(String id, String version) {
        Document doc = null
        log.debug("Loading document from database.")
        if (version) {
            doc = loadFromSql(id, version.toInteger(), GET_DOCUMENT_VERSION)
        } else {
            doc = loadFromSql(id, -1, GET_DOCUMENT)
        }
        return doc
    }

    private Document loadFromSql(String id, int version, String sql) {
        Document doc = null
        Connection connection = connectionPool.getConnection()
        PreparedStatement selectstmt
        ResultSet rs
        try {
            selectstmt = connection.prepareStatement(sql)
            selectstmt.setString(1, id)
            if (version > 0) {
                selectstmt.setInteger(2, version)
            }
            rs = selectstmt.executeQuery()
            if (rs.next()) {
                doc = whelk.createDocument(rs.getBytes("data"), mapper.readValue(rs.getString("entry"), Map), mapper.readValue(rs.getString("meta"), Map))
                log.debug("Retrieved document ${doc.identifier}.")
            } else {
                log.trace("No results returned for get($id)")
            }
        } finally {
            rs.close()
            selectstmt.close()
            connection.close()
        }
        return doc
    }

    @Override
    Document loadByAlternateIdentifier(String identifier) {
        return loadFromSql(GET_DOCUMENT_BY_ALTERNATE_ID)
    }

    @Override
    Iterable<Document> loadAll() {
        return loadAll(null,null,null)
    }

    @Override
    List<Document> loadAllVersions(String identifier) {
        throw UnsupportedOperationException("Not implemented yet!")
    }

    Iterable<Document> loadAll(String dataset, Date since = null, Date until = null) {
        throw UnsupportedOperationException("Not implemented yet!")
    }

    @Override
    void remove(String identifier, String dataset) {
        if (versioning) {
            log.debug("Creating tombstone record with id ${identifier}")
            store(createTombstone(identifier, dataset))
        } else {
            throw UnsupportedOperationException("Not implemented yet!")
        }
    }

    public void close() {
        log.info("Closing down postgresql connections.")
        try {
            statement.cancel()
            if (resultSet != null) {
                resultSet.close()
            }
        } catch (SQLException e) {
            log.warn("Exceptions on close. These are safe to ignore.", e)
        } finally {
            try {
                statement.close()
                conn.close()
            } catch (SQLException e) {
                log.warn("Exceptions on close. These are safe to ignore.", e)
            } finally {
                resultSet = null
                statement = null
                conn = null
            }
        }
    }
}
