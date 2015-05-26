package whelk.component

import groovy.util.logging.Slf4j as Log

import java.sql.*
import org.apache.commons.dbcp2.*
import org.postgresql.PGStatement

import whelk.*

@Log
class PostgreSQLStorage extends AbstractSQLStorage {

    String mainTableName, versionsTableName
    List<String> availableTypes

    String jdbcDriver = "org.postgresql.Driver"

    // SQL statements
    protected String UPSERT_DOCUMENT, INSERT_DOCUMENT_VERSION, GET_DOCUMENT, GET_DOCUMENT_VERSION, GET_ALL_DOCUMENT_VERSIONS, GET_DOCUMENT_BY_ALTERNATE_ID, LOAD_ALL_DOCUMENTS, LOAD_ALL_DOCUMENTS_WITH_LINKS, LOAD_ALL_DOCUMENTS_WITH_LINKS_BY_DATASET, LOAD_ALL_DOCUMENTS_BY_DATASET, DELETE_DOCUMENT_STATEMENT
    protected String LOAD_ALL_STATEMENT, LOAD_ALL_STATEMENT_WITH_DATASET

    PostgreSQLStorage(String componentId = null, Map settings) {
        this.contentTypes = settings.get('contentTypes', null)
        this.versioning = settings.get('versioning', false)
        this.connectionUrl = settings.get("databaseUrl")
        this.mainTableName = settings.get('tableName', null)
        this.availableTypes = settings.get('availableTypes', [])
        id = componentId
    }

    void componentBootstrap(String str) {
        log.info("Bootstrapping ${this.id}")
        if (!this.mainTableName) {
            this.mainTableName = str
        }
        if (versioning) {
            this.versionsTableName = "versions_" + mainTableName
        }
        UPSERT_DOCUMENT = "WITH upsert AS (UPDATE $mainTableName SET data = ?, entry = ?, ts = ?, deleted = ? WHERE identifier = ? RETURNING *) " +
            "INSERT INTO {tableName} (identifier, data, entry, ts, deleted) SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT * FROM upsert)"


        INSERT_DOCUMENT_VERSION = "INSERT INTO $versionsTableName (identifier,data,checksum,ts,entry) SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM $versionsTableName WHERE identifier = ? AND checksum = ?)"

        GET_DOCUMENT = "SELECT identifier,data,entry,deleted FROM $mainTableName WHERE identifier = ?"
        GET_DOCUMENT_VERSION = "SELECT identifier,data,entry,deleted FROM $versionsTableName WHERE identifier = ? AND checksum = ?"
        GET_ALL_DOCUMENT_VERSIONS = "SELECT identifier,data,entry FROM $versionsTableName WHERE identifier = ? ORDER BY ts"
        GET_DOCUMENT_BY_ALTERNATE_ID = "SELECT identifier,data,entry FROM $mainTableName WHERE entry @> '{ \"alternateIdentifiers\": [?] }'"
        LOAD_ALL_STATEMENT = "SELECT identifier,data,entry FROM $mainTableName WHERE ts >= ? AND ts <= ? AND identifier != ?  ORDER BY ts"
        LOAD_ALL_STATEMENT_WITH_DATASET = "SELECT identifier,data,entry FROM $mainTableName WHERE ts >= ? AND ts <= ? AND identifier != ? AND dataset = ? ORDER BY ts"
        LOAD_ALL_DOCUMENTS = "SELECT identifier,data,entry FROM $mainTableName WHERE ts >= ? AND ts <= ? ORDER BY ts"
        LOAD_ALL_DOCUMENTS_BY_DATASET = "SELECT identifier,data,entry FROM $mainTableName WHERE ts >= ? AND ts <= ? AND entry @> '{ \"dataset\": \"{dataset}\" }' ORDER BY ts"
        LOAD_ALL_DOCUMENTS_WITH_LINKS = """
            SELECT l.id as parent, r.identifier as identifier, r.data as data, r.entry as entry
            FROM (
                SELECT * FROM (
                    SELECT identifier as id, identifier as link FROM $mainTableName
                    UNION ALL
                    SELECT identifier as id, jsonb_array_elements_text(entry->'links') as link FROM $mainTableName
                ) AS links GROUP by id,link
            ) l JOIN $mainTableName r ON l.link = r.identifier ORDER BY l.id
            """
        LOAD_ALL_DOCUMENTS_WITH_LINKS_BY_DATASET = """
            SELECT l.id as parent, r.identifier as identifier, r.data as data, r.entry as entry
            FROM (
                SELECT * FROM (
                    SELECT identifier as id, identifier as link FROM $mainTableName WHERE dataset = ?
                    UNION ALL
                    SELECT identifier as id, jsonb_array_elements_text(entry->'links') as link FROM $mainTableName WHERE dataset = ?
                ) AS links GROUP by id,link
            ) l JOIN $mainTableName r ON l.link = r.identifier ORDER BY l.id
            """

        DELETE_DOCUMENT_STATEMENT = "DELETE FROM $mainTableName WHERE identifier = ?"
    }

    @Override
    void createTables() {
        Connection connection = connectionPool.getConnection()
        Statement stmt = connection.createStatement();
        stmt.executeUpdate("CREATE TABLE IF NOT EXISTS $mainTableName ("
            +"identifier text primary key,"
            +"data jsonb not null,"
            +"ts timestamp with time zone not null default now(),"
            +"entry jsonb not null,"
            +"deleted boolean not null default false"
            +")");
        availableTypes.each {
            log.debug("Creating child table $it")
            def result = stmt.executeUpdate("CREATE TABLE IF NOT EXISTS ${mainTableName}_${it} ("
                    +"CHECK (entry->>'dataset' = '${it}'), PRIMARY KEY (identifier) ) INHERITS (${mainTableName})")
            log.debug("Creating indexes for $it")
            try {
                stmt.executeUpdate("CREATE INDEX idx_${mainTableName}_${it}_dataset ON ${mainTableName}_${it} (entry->>'dataset')")
                stmt.executeUpdate("CREATE INDEX idx_${mainTableName}_${it}_ts ON ${mainTableName}_${it} (ts)")
                stmt.executeUpdate("CREATE INDEX idx_${mainTableName}_${it}_alive ON ${mainTableName}_${it} (identifier) WHERE deleted is not true")
                stmt.executeUpdate("CREATE INDEX idx_${mainTableName}_${it}_entry ON ${mainTableName}_${it} USING gin (entry jsonb_path_ops)")
                stmt.executeUpdate("CREATE INDEX idx_${mainTableName}_${it}_data ON ${mainTableName}_${it} USING gin (data)")
            } catch (org.postgresql.util.PSQLException pgsqle) {
                log.trace("Indexes on $mainTableName / $id already exists.")
            }
        }
        if (versioning) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS $versionsTableName ("
                +"id serial,"
                +"identifier text not null,"
                +"data jsonb not null,"
                +"entry jsonb not null,"
                +"checksum char(32) not null,"
                +"ts timestamp with timezone not null default now(),"
                +"UNIQUE (identifier, checksum)"
                +")");
            try {
                stmt.executeUpdate("CREATE INDEX ${versionsTableName}_identifier ON ${versionsTableName} (identifier)")
                stmt.executeUpdate("CREATE INDEX ${versionsTableName}_ts ON ${versionsTableName} (ts)")
                stmt.executeUpdate("CREATE INDEX ${versionsTableName}_checksum ON ${versionsTableName} (checksum)")
            } catch (org.postgresql.util.PSQLException pgsqle) {
                log.trace("Indexes on $mainTableName / $id already exists.")
            }
        }
        connection.close()
    }

    @Override
    boolean store(Document doc, boolean withVersioning = versioning) {
        log.debug("Document ${doc.identifier} checksum before save: ${doc.checksum}")
        if (versioning && withVersioning) {
            if (!saveVersion(doc)) {
                return true // Same document already in storage.
            }
        }
        assert doc.dataset
        log.debug("Saving document ${doc.identifier} (with checksum: ${doc.checksum})")
        //displayConnectionPoolStatus("store")
        Connection connection = connectionPool.getConnection()
        PreparedStatement insert = connection.prepareStatement(UPSERT_DOCUMENT.replaceAll(/\{tableName\}/, mainTableName + "_" + doc.dataset))
        try {
            insert.setObject(1, doc.dataAsJson, java.sql.Types.OTHER)
            insert.setObject(2, doc.entryAsJson, java.sql.Types.OTHER)
            insert.setTimestamp(3, new Timestamp(doc.modified))
            insert.setBoolean(4, doc.deleted)
            insert.setString(5, doc.identifier)
            insert.setString(6, doc.identifier)
            insert.setObject(7, doc.dataAsJson, java.sql.Types.OTHER)
            insert.setObject(8, doc.entryAsJson, java.sql.Types.OTHER)
            insert.setTimestamp(9, new Timestamp(doc.modified))
            insert.setBoolean(10, doc.deleted)
            insert.executeUpdate()
            return true
        } catch (Exception e) {
            log.error("Failed to save document: ${e.message}")
            throw e
        } finally {
            connection.close()
            log.debug("[store] Closed connection.")
        }
        return false
    }

    boolean saveVersion(Document doc) {
        log.debug("Save version")
        //displayConnectionPoolStatus("saveVersion")
        Connection connection = connectionPool.getConnection()
        PreparedStatement insvers = connection.prepareStatement(INSERT_DOCUMENT_VERSION)
        try {
            log.debug("Trying to save a version of ${doc.identifier} with checksum ${doc.checksum}. Modified: ${doc.modified}")
            insvers.setString(1, doc.identifier)
            insvers.setObject(2, doc.dataAsJson, java.sql.Types.OTHER)
            insvers.setString(3, doc.checksum)
            insvers.setTimestamp(4, new Timestamp(doc.modified))
            insvers.setObject(5, doc.entryAsJson, java.sql.Types.OTHER)
            insvers.setString(6, doc.identifier)
            insvers.setString(7, doc.checksum)
            int updated =  insvers.executeUpdate()
            log.debug("${updated > 0 ? 'New version saved.' : 'Already had same version'}")
            return (updated > 0)
        } catch (Exception e) {
            log.error("Failed to save document version: ${e.message}")
            throw e
        } finally {
            connection.close()
            log.debug("[saveVersion] Closed connection.")
        }
    }

    @Override
    void bulkStore(final List docs, String dataset) {
        if (!docs || docs.isEmpty()) {
            return
        }
        log.debug("Bulk storing ${docs.size()} documents into dataset $dataset")
        //displayConnectionPoolStatus("bulkStore")
        Connection connection = connectionPool.getConnection()
        PreparedStatement batch = connection.prepareStatement(UPSERT_DOCUMENT.replaceAll(/\{tableName\}/, mainTableName + "_" + dataset))
        PreparedStatement ver_batch = connection.prepareStatement(INSERT_DOCUMENT_VERSION)
        try {
            docs.each { doc ->
                if (versioning) {
                    ver_batch.setString(1, doc.identifier)
                    ver_batch.setObject(2, doc.dataAsJson, java.sql.Types.OTHER)
                    ver_batch.setString(3, doc.checksum)
                    ver_batch.setTimestamp(4, new Timestamp(doc.modified))
                    ver_batch.setObject(5, doc.entryAsJson, java.sql.Types.OTHER)
                    ver_batch.setString(6, doc.identifier)
                    ver_batch.setString(7, doc.checksum)
                    ver_batch.addBatch()
                }
                batch.setObject(1, doc.dataAsJson, java.sql.Types.OTHER)
                batch.setObject(2, doc.entryAsJson, java.sql.Types.OTHER)
                batch.setTimestamp(3, new Timestamp(doc.modified))
                batch.setBoolean(4, doc.deleted)
                batch.setString(5, doc.identifier)
                batch.setString(6, doc.identifier)
                batch.setObject(7, doc.dataAsJson, java.sql.Types.OTHER)
                batch.setObject(8, doc.entryAsJson, java.sql.Types.OTHER)
                batch.setTimestamp(9, new Timestamp(doc.modified))
                batch.setBoolean(10, doc.deleted)
                batch.addBatch()
            }
            ver_batch.executeBatch()
            batch.executeBatch()
            log.debug("Stored ${docs.size()} documents with dataset $dataset (versioning: ${versioning})")
        } catch (Exception e) {
            log.error("Failed to save batch: ${e.message}")
            throw e
        } finally {
            connection.close()
            log.debug("[bulkStore] Closed connection.")
        }
    }

    Document load(String id) {
        return load(id, null)
    }

    @Override
    Document load(String id, String version) {
        Document doc = null
        if (version && version.isInteger()) {
            int v = version.toInteger()
            def docList = loadAllVersions(id)
            if (v < docList.size()) {
                doc = docList[v]
            }
        } else if (version) {
            doc = loadFromSql(id, version, GET_DOCUMENT_VERSION)
        } else {
            doc = loadFromSql(id, null, GET_DOCUMENT)
        }
        return doc
    }

    private void displayConnectionPoolStatus(String callingMethod) {
        log.debug("[${callingMethod}] cpool numActive: ${connectionPool.numActive}")
        log.debug("[${callingMethod}] cpool numIdle: ${connectionPool.numIdle}")
        log.debug("[${callingMethod}] cpool maxTotal: ${connectionPool.maxTotal}")
    }

    private Document loadFromSql(String id, String checksum, String sql) {
        Document doc = null
        log.debug("loadFromSql $id ($sql)")
        //displayConnectionPoolStatus("loadFromSql")
        Connection connection = connectionPool.getConnection()
        //connection = DriverManager.getConnection("jdbc:postgresql://localhost/whelk")
        log.debug("Got connection.")
        PreparedStatement selectstmt
        ResultSet rs
        try {
            selectstmt = connection.prepareStatement(sql)
            log.trace("Prepared statement")
            if (id) {
                selectstmt.setString(1, id)
            }
            if (checksum) {
                selectstmt.setString(2, checksum)
            }
            log.trace("About to execute")
            rs = selectstmt.executeQuery()
            log.trace("Executed.")
            if (rs.next()) {
                doc = whelk.createDocument(mapper.readValue(rs.getString("data"), Map), mapper.readValue(rs.getString("entry"), Map))
            } else {
                log.trace("No results returned for get($id)")
            }
        } finally {
            connection.close()
            log.debug("[loadFromSql] Closed connection.")
        }
        return doc
    }

    @Override
    Document loadByAlternateIdentifier(String identifier) {
        String sql = GET_DOCUMENT_BY_ALTERNATE_ID.replace("?", '"' + identifier + '"')
        return loadFromSql(null, null, sql)
    }

    @Override
    List<Document> loadAllVersions(String identifier) {
        List<Document> docList = []
        if (versioning) {
            Connection connection = connectionPool.getConnection()
            PreparedStatement selectstmt
            ResultSet rs
            try {
                selectstmt = connection.prepareStatement(GET_ALL_DOCUMENT_VERSIONS)
                selectstmt.setString(1, identifier)
                rs = selectstmt.executeQuery()
                int v = 0
                while (rs.next()) {
                    def doc = whelk.createDocument(mapper.readValue(rs.getString("data"), Map), mapper.readValue(rs.getString("entry"), Map))
                    doc.version = v++
                    docList << doc
                }
            } finally {
                connection.close()
                log.debug("[loadAllVersions] Closed connection.")
            }
        }
        return docList
    }

    @Override
    Iterable<Document> loadAll(String dataset) {
        return loadAllDocuments(dataset, false)
    }

    private Iterable<Document> loadAllDocuments(String dataset, boolean withLinks, Date since = null, Date until = null) {
        log.debug("Load all called with dataset: ${dataset}. withLinks: $withLinks")
        return new Iterable<Document>() {
            Iterator<Document> iterator() {
                Connection connection = connectionPool.getConnection()
                connection.setAutoCommit(false)
                PreparedStatement loadAllStatement
                long untilTS = until?.getTime() ?: PGStatement.DATE_POSITIVE_INFINITY
                long sinceTS = since?.getTime() ?: 0L

                if (dataset) {
                    if (withLinks) {
                        loadAllStatement = connection.prepareStatement(LOAD_ALL_DOCUMENTS_WITH_LINKS_BY_DATASET)
                    } else {
                        loadAllStatement = connection.prepareStatement(LOAD_ALL_DOCUMENTS_BY_DATASET.replaceAll(/\{dataset\}/, dataset))
                    }
                } else {
                    if (withLinks) {
                        loadAllStatement = connection.prepareStatement(LOAD_ALL_DOCUMENTS_WITH_LINKS)
                    } else {
                        loadAllStatement = connection.prepareStatement(LOAD_ALL_DOCUMENTS)
                    }
                }
                loadAllStatement.setFetchSize(100)
                loadAllStatement.setTimestamp(1, new Timestamp(sinceTS))
                loadAllStatement.setTimestamp(2, new Timestamp(untilTS))

                if (dataset) {
                    if (withLinks) {
                        loadAllStatement.setString(3, dataset)
                    }
                }
                ResultSet rs = loadAllStatement.executeQuery()

                boolean more = rs.next()
                if (!more) {
                    try {
                        connection.commit()
                        connection.setAutoCommit(true)
                    } finally {
                        connection.close()
                    }
                }

                return new Iterator<Document>() {
                    @Override
                    public Document next() {
                        Document doc = whelk.createDocument(mapper.readValue(rs.getString("data"), Map), mapper.readValue(rs.getString("entry"), Map))
                        more = rs.next()
                        if (!more) {
                            try {
                                connection.commit()
                                connection.setAutoCommit(true)
                            } finally {
                                connection.close()
                            }
                        }
                        return doc
                    }

                    @Override
                    public boolean hasNext() {
                        return more
                    }
                }
            }
        }
    }

    @Override
    void remove(String identifier, String dataset) {
        if (versioning) {
            log.debug("Creating tombstone record with id ${identifier}")
            store(createTombstone(identifier, dataset))
        } else {
            Connection connection = connectionPool.getConnection()
            PreparedStatement delstmt = connection.prepareStatement(DELETE_DOCUMENT_STATEMENT)
            try {
                delstmt.setString(1, identifier)
                delstmt.executeUpdate()
            } finally {
                connection.close()
                log.debug("[remove] Closed connection.")
            }
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

    @Override
    public Map getStatus() {
        def status = [:]
        status['mainTable'] = mainTableName
        status['versioning'] = versioning
        if (versioning) {
            status['versionsTableName'] = versionsTableName
        }
        status['contentTypes'] = contentTypes
        status['availableTypes'] = availableTypes
        status['databaseUrl'] = connectionUrl
        return status
    }
}
