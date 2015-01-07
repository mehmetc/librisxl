package whelk.converter

import com.fasterxml.jackson.core.type.TypeReference
import groovy.json.JsonSlurper
import org.json.simple.JSONObject
import se.kb.libris.util.marc.Datafield
import se.kb.libris.util.marc.MarcRecordBuilder
import se.kb.libris.util.marc.impl.ControlfieldImpl
import se.kb.libris.util.marc.impl.DatafieldImpl
import se.kb.libris.util.marc.impl.MarcRecordImpl
import se.kb.libris.util.marc.io.MarcRecordReader
import se.kb.libris.util.marc.io.MarcRecordWriter
import se.kb.libris.util.marc.io.MarcXmlRecordWriter
import se.kb.libris.util.marc.io.DomSerializer
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.w3c.dom.*;

import java.text.Normalizer
//import org.json.simple.*
import groovy.util.logging.Slf4j as Log

import se.kb.libris.util.marc.Controlfield
import se.kb.libris.util.marc.MarcRecord
import se.kb.libris.util.marc.io.Iso2709MarcRecordReader
import se.kb.libris.util.marc.io.MarcXmlRecordReader
import org.codehaus.jackson.map.ObjectMapper
import org.codehaus.jackson.node.ObjectNode

/**
 *
 * A mechanical transcription of {@link MarcRecord}s into JSON. The result is
 * compliant with the <a href="http://dilettantes.code4lib.org/blog/category/marc-in-json/">MARC-in-JSON</a> JSON schema.
 */
@Log
class MarcJSONConverter {
    protected final static ObjectMapper mapper = new ObjectMapper();
    static String old_toJSONString(MarcRecord record) {
        def builder = new groovy.json.JsonBuilder()
        builder {
            "leader"(record.leader)
            "fields"(record.fields.collect {[
                (it.tag): (it instanceof Controlfield)? (it.data) : [
                    ind1: it.getIndicator(0),
                    ind2:  it.getIndicator(1),
                    subfields: it.subfields.collect { [(it.code): it.data] }
                ]
            ]})
        }
        return builder.toString()
    }
    /* Removed simple-json dependency from build.
    static String not_quite_so_old_toJSONString(MarcRecord record) {
        def json = new JSONObject()
        def fields = new JSONArray()

        record.fields.each {
            def field = new JSONObject()
            if (it instanceof Controlfield) {
                field.put(it.tag, it.data)
            } else {
                def datafield = new JSONObject()
                datafield.put("ind1", "" + it.getIndicator(0))
                datafield.put("ind2", "" + it.getIndicator(1))
                def subfields = new JSONArray()
                it.subfields.each {
                    def subfield = new JSONObject()
                    subfield.put(it.code, it.data);
                    subfields.add(subfield)
                }
                datafield.put("subfields", subfields)
                field.put(it.tag, datafield)
            }
            fields.add(field)
        }
        json.put("leader", record.leader)
        json.put("fields", fields)

        return json.toString()
    }
    */

    private static ObjectNode toObjectNode(MarcRecord record) {
        def json = mapper.createObjectNode()
        def fields = mapper.createArrayNode()
        record.fields.each {
            def field = mapper.createObjectNode()
            if (it instanceof Controlfield) {
                field.put(it.tag, it.data)
            } else {
                def datafield = mapper.createObjectNode()
                datafield.put("ind1", "" + it.getIndicator(0))
                datafield.put("ind2", "" + it.getIndicator(1))
                def subfields = mapper.createArrayNode()
                it.subfields.each {
                    def subfield = mapper.createObjectNode()
                    subfield.put(Character.toString(it.code), it.data) //normalizeString(it.data))
                    subfields.add(subfield)
                }
                datafield.put("subfields", subfields)
                field.put(it.tag, datafield)
            }
            fields.add(field)
        }
        json.put("leader", record.leader)
        json.put("fields", fields)
        return json
    }

    static String toJSONString(MarcRecord record) {
        return toObjectNode(record).toString()
    }

    static Map toJSONMap(MarcRecord record) {
        def node = toObjectNode(record)
        return mapper.readValue(node, Map)
    }

    static InputStream getNormalizedInputStreamFromFile(File f) {
        String unicodeString = f.getText("utf8")
        if (!Normalizer.isNormalized(unicodeString, Normalizer.Form.NFC)) {
            String newString = Normalizer.normalize(unicodeString, Normalizer.Form.NFC)
            return new ByteArrayInputStream(newString.getBytes("UTF-8"))
        }
        return f.newInputStream()
    }

    static void main(args) {
        /*
        MarcRecord record = new File(args[0]).withInputStream {
        new Iso2709MarcRecordReader(it).readRecord()
        }
        */
        MarcRecord record = null
        if (args.length > 1 && args[0] == "-xml")  {
            record = new MarcXmlRecordReader(getNormalizedInputStreamFromFile(new File(args[1]))).readRecord()
        }
        if (record == null) {
            record = new Iso2709MarcRecordReader(getNormalizedInputStreamFromFile(new File(args[0]))).readRecord()
        }
        println toJSONString(record)
        /*println not_quite_so_old_toJSONString(record)*//*.replaceAll(
            /(?m)\{\s+(\S+: "[^"]+")\s+\}/, '{$1}')*/
    }
}
@Log
class JSONMarcConverter {
    protected final static ObjectMapper mapper = new ObjectMapper();
    static MarcRecord fromJson(String json) {

        MarcRecord record = new MarcRecordImpl();
        Map resultJson = mapper.readValue(json, Map);
        def fields = resultJson.get("fields");
        def leader = resultJson.get("leader");

        record.setLeader(leader);

        for (Map field in fields) {
            field.each {String fieldKey, fieldValue ->

                if (fieldKey.isInteger() && fieldKey.toInteger() <= 10){
                    Controlfield controlfield = new ControlfieldImpl(fieldKey, fieldValue);
                    record.addField(controlfield);
                }else {
                    Datafield datafield = new DatafieldImpl(fieldKey);
                    if (fieldValue instanceof Map) {
                        fieldValue.each {dataKey, dataValue ->
                            if (dataValue instanceof ArrayList) {
                                for (Map subFields in dataValue) {
                                    subFields.each {subKey, subValue ->
                                       datafield.addSubfield(subKey as char, subValue);
                                    }
                                }
                            }else {
                                int ind = 1;
                                if (dataKey.equals("ind1"))
                                    ind = 0;
                                datafield.setIndicator(ind, (dataValue as char));
                            }
                        }
                        record.addField(datafield);
                    }
                }
            }
        }
        return record;
    }

    static String marcRecordAsXMLString(MarcRecord record) {
        DocumentFragment docFragment = DomSerializer.serialize(record, javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument())
        StringWriter sw = new StringWriter()
        Source source = new DOMSource(docFragment)
        Result result = new StreamResult(sw)
        Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty("omit-xml-declaration", "yes")

        try {
            transformer.setOutputProperty("encoding", "UTF-8")
            transformer.transform(source, result)
        } catch (javax.xml.transform.TransformerException e) {
            System.err.println(e.getMessage());
        }

        return sw.toString()
    }
}
