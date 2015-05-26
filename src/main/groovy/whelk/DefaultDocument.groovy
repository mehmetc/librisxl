package whelk

import groovy.transform.TypeChecked
import groovy.util.logging.Slf4j as Log

import java.io.*
import java.util.*
import java.nio.ByteBuffer
import java.lang.annotation.*
import java.security.MessageDigest

import org.codehaus.jackson.*
import org.codehaus.jackson.map.*
import org.codehaus.jackson.annotate.JsonIgnore

import whelk.*
import whelk.component.*
import whelk.exception.*


@Log
class DefaultDocument implements Document {
    protected byte[] data = new byte[0]
    Map entry = [:]

    @JsonIgnore
    private Set<String> identifiers = new LinkedHashSet<String>()
    @JsonIgnore
    private Set<String> types = new LinkedHashSet<String>()

    @JsonIgnore
    static final ObjectMapper mapper = new ObjectMapper()

    DefaultDocument() {
        entry = [:]
        setCreated(new Date().getTime())
    }

    @JsonIgnore
    String getIdentifier() {
        entry['identifier']
    }

    @JsonIgnore
    List<String> getIdentifiers() {
        def idList = [getIdentifier()]
        idList.addAll(identifiers)
        return idList
    }

    @JsonIgnore
    List<String> getTypes() {
        def tList = []
        tList.addAll(types)
        return tList
    }

    @JsonIgnore
    String getDataAsString() {
        return new String(getData(), "UTF-8")
    }

    @JsonIgnore
    String getChecksum() { return entry.get("checksum") }

    String toJson() {
        log.trace("Serializing document.")
        String jsonString = mapper.writeValueAsString(this)
        log.trace("Result of serialization: $jsonString")
        return jsonString
    }

    Map toMap() {
        return mapper.convertValue(this, Map)
    }

    byte[] getData(long offset, long length) {
        byte[] ret = new byte[(int)length]
        System.arraycopy(getData(), (int)offset, ret, 0, (int)length)
        return ret
    }

    byte[] getData() {
        return data
    }

    @JsonIgnore
    String getEntryAsJson() {
        return mapper.writeValueAsString(entry)
    }

    @JsonIgnore
    String getContentType() { entry[CONTENT_TYPE_KEY] }

    @JsonIgnore
    long getCreated() {
        return (entry.get(CREATED_KEY, 0L))
    }

    @JsonIgnore
    long getModified() {
        return entry.get(MODIFIED_KEY, 0L)
    }

    @JsonIgnore
    Date getModifiedAsDate() {
        return new Date(getModified())
    }

    @JsonIgnore
    int getVersion() {
        return entry.get("version", 0)
    }

    @JsonIgnore
    String getDataset() {
        return entry.get("dataset")
    }

    long updateModified() {
        setModified(new Date().getTime())
        return getModified()
    }

    void setIdentifier(String identifier) {
        entry['identifier'] = identifier
    }

    protected void setCreated(long ts) {
        this.entry[CREATED_KEY] = ts
        if (getModified() < 1) {
            this.entry[MODIFIED_KEY] = ts
        }
    }

    void setModified(long mt) {
        this.entry[MODIFIED_KEY] = mt
    }

    void setVersion(int v) {
        this.entry["version"] = v
    }

    void setData(byte[] data, boolean calcChecksum = true) {
        this.data = data
        if (calcChecksum) {
            calculateChecksum(this.data, entry.get(EXTRADATA_KEY, [:]).toString().getBytes())
        }
    }

    void setDataset(String ds) {
        entry.put("dataset", ds)
    }

    void addIdentifier(String id) {
        identifiers.add(id)
        entry["alternateIdentifiers"] = identifiers
    }

    void addType(String t) {
        types.add(t)
        entry.put(TYPES_KEY, types)
    }

    /*
     * Convenience methods
     */
    Document withIdentifier(String i) {
        this.entry['identifier'] = i
        return this
    }

    Document withContentType(String ctype) {
        setContentType(ctype)
        return this
    }

    void setContentType(String ctype) {
        this.entry[CONTENT_TYPE_KEY] = ctype
    }

    protected Document withCreated(long ts) {
        setCreated(ts)
        return this
    }

    Document withModified(long mt) {
        setModified(mt)
        return this
    }

    Document withVersion(long v) {
        setVersion((int)v)
        return this
    }

    Document withVersion(int v) {
        setVersion(v)
        return this
    }

    Document withData(byte[] data) {
        setData(data)
        return this
    }

    Document withData(String dataString) {
        return withData(dataString.getBytes("UTF-8"))
    }

    Document withDataset(String dataset) {
        setDataset(dataset)
        return this
    }

    /**
     * Convenience method to set data from dictionary, assuming data is to be stored as json.
     */
    void setEntry(Map entryData) {
        log.debug("Clearing entry")
        this.entry = [:]
        withEntry(entryData)
    }

    Document withEntry(Map entrydata) {
        log.debug("withEntry: $entrydata")
        if (entrydata?.containsKey("identifier")) {
            this.identifier = entrydata["identifier"]
        }
        if (entrydata?.containsKey(CREATED_KEY)) {
            setCreated(entrydata.get(CREATED_KEY))
        }
        if (entrydata?.containsKey(MODIFIED_KEY)) {
            setModified(entrydata.get(MODIFIED_KEY))
        }
        if (entrydata?.containsKey(CONTENT_TYPE_KEY)) {
            setContentType(entrydata.get(CONTENT_TYPE_KEY))
        }
        if (entrydata?.containsKey(ALT_IDENTS_KEY)) {
            this.identifiers = entrydata.get(ALT_IDENTS_KEY)
        }
        if (entrydata?.containsKey(TYPES_KEY)) {
            this.types = entrydata.get(TYPES_KEY)
        }
        if (entrydata != null) {
            this.entry.putAll(entrydata)
            if (checksum) {
                this.entry['checksum'] = checksum
            }
        }
        return this
    }
    /**
     * Expects a JSON string containing meta and entry as dictionaries.
     * It's the reverse of getEntryAsJson().
     */
    Document withEntryAsJson(String jsonEntry) {
        Map entry = mapper.readValue(jsonEntry, Map)
        return withEntry(entry)
    }

    @JsonIgnore
    boolean isJson() {
        getContentType() ==~ /application\/(\w+\+)*json/ || getContentType() ==~ /application\/x-(\w+)-json/
    }

    @JsonIgnore
    boolean isDeleted() {
        return entry.get("deleted", false)
    }

    protected void calculateChecksum(byte[] databytes, byte[] metabytes) {
        MessageDigest m = MessageDigest.getInstance("MD5")
        m.reset()
        //byte[] metabytes = meta.toString().getBytes()
        byte[] checksumbytes = new byte[databytes.length + metabytes.length];
        System.arraycopy(databytes, 0, checksumbytes, 0, databytes.length);
        System.arraycopy(metabytes, 0, checksumbytes, databytes.length, metabytes.length);
        m.update(checksumbytes)
        byte[] digest = m.digest()
        BigInteger bigInt = new BigInteger(1,digest)
        String hashtext = bigInt.toString(16)
        log.debug("calculated checksum: $hashtext")
        this.entry['checksum'] = hashtext
    }
}
