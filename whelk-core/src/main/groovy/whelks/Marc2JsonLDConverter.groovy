package se.kb.libris.whelks.plugin

import java.text.SimpleDateFormat

import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*
import se.kb.libris.whelks.exception.*

import groovy.util.logging.Slf4j as Log

import org.codehaus.jackson.map.ObjectMapper


@Log
class Marc2JsonLDConverter extends BasicPlugin implements WhelkAware, FormatConverter, IndexFormatConverter {
    final static String RAW_LABEL = "marc21"
    final static String ABOUT_LABEL = "about"
    final static String INSTANCE_LABEL = "instanceOf"
    String requiredContentType = "application/json"
    String requiredFormat = "marc21"
    ObjectMapper mapper

    def marcref
    def marcmap
    Whelk whelk

    Marc2JsonLDConverter() {
        mapper = new ObjectMapper()
        InputStream is = this.getClass().getClassLoader().getResourceAsStream("marc_refs.json")
        this.marcref = mapper.readValue(is, Map)
        is = this.getClass().getClassLoader().getResourceAsStream("marcmap.json")
        this.marcmap = mapper.readValue(is, Map)
    }

   static main(args) {
        def uri = new URI(args[0])
        def source = new File(args[1])
        def destDir = new File(args[2])
        def dest = new File(destDir, source.name)
        def mapper = new ObjectMapper()
        log.info("source: $source")
        def inData = source.withInputStream { mapper.readValue(it, Map) }
        def converter = new Marc2JsonLDConverter()
        def outjson = converter.createJson(uri, inData)
        log.info("dest: $dest")
        dest << mapper.defaultPrettyPrintingWriter().writeValueAsString(outjson).getBytes("utf-8")
    }

    def mapDefault(String code, String value) {
        if (marcref.fields[code]) {
            def out = [(marcref.fields[code]): value]
            return out
        } else if (marcref.fields[code] == false){
            log.debug("Failed to map $code")
            return false
        }
        return null
    }

    def mapDefault(String code, Date value) {
        if (marcref.fields[code]) {
            def sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S")
            def out = [(marcref.fields[code]): sdf.format(value)]
            return out
        } else if (marcref.fields[code] == false){
            log.debug("Failed to map $code")
            return false
        }
        return null
    }

    def mapDefault(String code, Map json) {
        boolean complete = true
        def out = [:]
        log.trace("mapDefault: $code = $json")
        json.get("subfields").each {
            it.each { k, v ->
                if (marcref.fields.get(code) == null) {
                    return null
                }
                def label = marcref.fields.get(code)?.get(k)
                if (label instanceof Map) {
                    assignValue(out, label, v)
                } else if (label instanceof List) {
                    label.each {
                        if (out.containsKey(it)) {
                            if (!(out[(it)] instanceof List)) {
                                def cval = out[(it)]
                                out[(it)] = [cval]
                            }
                            out[(it)] << v
                        } else {
                            out[(it)] = v
                        }
                    }
                } else if (label) {
                    out[label] = v
                } else if (label == null) {
                    log.debug("Failed to map ${code}.${k}")
                    complete = false
                }
            }
        }
        if (complete) {
            log.trace("default mapping: $out")
            return out
        }
        //return [(RAW_LABEL): ["fields":[(code):json]]]
        return false
    }

    private void assignValue(Map out, Map refmap, def value) {
        refmap.each { rk, rv ->
            if (!(rv instanceof Map)) {
                if (out[(rk)]) {
                    out[(rk)] << [(rv):value]
                } else {
                    out[(rk)] = [(rv):value]
                }
            } else {
                if (out.containsKey(rk)) {
                    if (!(out.get(rk) instanceof Map)) {
                        def ov = out.get(rk)
                        out[(rk)] = ["value":ov]
                    }
                } else {
                    out[(rk)] = [:]
                }
                assignValue(out[(rk)], rv, value)
            }
        }
    }

    def mapIsbn(json) {
        def out = [:]
        boolean complete = true
        json['subfields'].each { it.each { key, value ->
            switch (key) {
                case "a":
                    log.trace("isbn a: $value")
                    if (value.contains(" ")) {
                        out["isbn"] = value.split(" ")[0].replaceAll("-", "")
                        out["isbnNote"] = value.split(" ")[1]
                    } else {
                        out["isbn"] = value.replaceAll("-", "")
                    }
                    break;
                case "c":
                    log.trace("isbn c: $value")
                    out["termsOfAvailability"]=["literal":value]
                    break;
                case "z":
                    log.trace("isbn z: $value")
                    out["deprecatedIsbn"]=value
                    break;
                default:
                    log.trace("isbn unknown: $key == $value")
                    complete = false
                    break;
            }
        } }
        if (complete) {
            return out
        }
        //return [(RAW_LABEL):["fields":["020":json]]]
        return false
    }

    def mapOtherIdentifier(code, json) {
        def out = [:]
        boolean complete = true
        def selectedLabel
        log.trace("ind1: ${json.ind1}")
        switch((json["ind1"] as String)) {
            case "0":
                selectedLabel = "isrc"
                break;
            case "1":
                selectedLabel = "upc"
                break;
            case "2":
                selectedLabel = "ismn"
                break;
            case "3":
                selectedLabel = "ean"
                break;
            case "4":
                selectedLabel = "sici"
                break;
            case "7":
                selectedLabel = "_other"
                break;
            case "8":
                selectedLabel = "unspecifiedStandardIdentifier"
                break;
        }
        log.trace("selectedLabel : $selectedLabel")
        if (!selectedLabel) {
            log.trace("Unable to find a label for ${json.ind1}")
            complete = false
        } else {
            json["subfields"].each {
                it.each { key, value ->
                    switch (key) {
                        case "a":
                            out[selectedLabel] = value
                            break
                        case "2":
                            out["identifier"] = ["@type":"Identifier","identifierScheme":value,"identifierValue":out["_other"]]
                            out.remove("_other")
                            break;
                        default:
                            log.trace("No rule for key $key")
                            complete = false
                            break
                    }
                }
            }
        }
        if (complete) {
            log.trace("mapped other identifier: $out")
            return out
        }
        return false
    }

    def mapPerson(code, json) {
        def out = [:]
        if ((code as int) % 100 == 0) {
            out["@type"] = "Person"
        }
        if ((code as int) % 100 == 10) {
            out["@type"] = "Organization"
        }
        if ((code as int) % 100 == 11) {
            out["@type"] = "Conference"
        }
        boolean complete = true
        log.trace("subfields: " + json['subfields'])
        json['subfields'].each {
            log.trace("subfield: $it")
            it.each { key, value ->
                switch (key) {
                    case "a":
                    value = value.trim().replaceAll(/,$/, "")
                    out["authoritativeName"] = value
                    def n = value.split(", ")
                    if (json["ind1"] == "1" && n.size() > 1) {
                        out["familyName"] = n[0]
                        out["givenName"] = n[1]
                        out["name"] = n[1] + " " + n[0]
                    } else {
                        out["name"] = value
                    }
                    break;
                    case "d":
                    def d = value.split("-")
                    out["birthYear"] = d[0]
                    if (d.length > 1) {
                        out["deathYear"] = d[1]
                    }
                    if (out["authoritativeName"]) {
                        out["authorizedAccessPoint"] = out["authoritativeName"] + ", $value"
                    }
                    break;
                    case "4":
                        break;
                    case "6":
                    //TODO: create linked resource for alternateGraphicRepresentation
                        break;
                    default:
                        complete = false
                        break;
                }
            }
        }
        if (complete) {
            return out
        } else {
            return false
        }
    }

    def mapPublishingInfo(code, json) {
        def out = [:]
        boolean complete = true
        json["subfields"].each {
            it.each { key, value ->
                switch (key) {
                    case "a":
                        value = value.replaceAll(/:$/, "").trim()
                        out["placeOfPublication"]=["@type":"Place","label":value]
                        break
                    case "b":
                        value = value.replaceAll(/,$/, "").trim()
                        out["publisher"]=["@type":"Organization", "name":value]
                        break
                    case "c":
                        value = value.replaceAll(/;$/, "").trim()
                        out["pubDate"] = ["@type":"year","@value":value]
                        break
                    case "e":
                        value = value.replaceAll(/[()]/, "").trim()
                        out["placeOfManufacture"] = ["@type":"Place","label":value]
                        break
                    default:
                        complete = false
                        break
                }
            }
        }
        if (complete) {
            return out
        }
        return false
    }

    // TODO: Break out the switch
    def mapCreator(code, json) {
        def out = [:]
        boolean complete = true
        json["subfields"].each {
            it.each { key, value ->
                switch (key) {
                    case "a":
                        out["creator"]=["@type":"Organization", "abbr":value]
                        break
                    default:
                        complete = false
                        break
                }
            }
        }
        if (complete) {
            return out
        }
        return false

    }

    def mapField(code, json, outjson) {
        switch(code) {
            case "020":
                def i = mapIsbn(json)
                if (i) {
                    def isbn = [(ABOUT_LABEL):i]
                    outjson = mergeMap(outjson, isbn)
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break
            case "024":
                def i = mapOtherIdentifier(code, json)
                if (i) {
                    outjson = mergeMap(outjson, [(ABOUT_LABEL):i])
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break
            case "040":
                def c = mapCreator(code, json)
                if (c) {
                    outjson = mergeMap(outjson, c)
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break;
            case "100":
            case "700":
                def p = mapPerson(code, json)
                if (p) {
                    def relcode = subfieldCode("4", json["subfields"])
                    log.trace("relcode: $relcode")
                    def relator = marcref.relators[relcode]
                    log.trace("mapPerson found relator $relator")
                    if (relator && relator != "author") {
                        if (relator in marcref.levels[INSTANCE_LABEL]) {
                            outjson = createNestedMapStructure(outjson, [ABOUT_LABEL, INSTANCE_LABEL, relator], [])
                            outjson[ABOUT_LABEL][INSTANCE_LABEL][relator] << p
                        } else {
                            outjson = createNestedMapStructure(outjson, [ABOUT_LABEL, relator], [])
                            outjson[ABOUT_LABEL][relator] << p
                        }
                    } else {
                        outjson = createNestedMapStructure(outjson, [ABOUT_LABEL, INSTANCE_LABEL, "authorList"], [])
                        outjson[ABOUT_LABEL][INSTANCE_LABEL]["authorList"] << p
                    }
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break;
            case "260":
                def pubMapped = mapPublishingInfo(code, json)
                if (pubMapped) {
                    outjson = mergeMap(outjson, [(ABOUT_LABEL):pubMapped])
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break;
            default:
                def jldMapped = mapDefault(code, json)
                log.trace("retrieved $jldMapped")
                if (jldMapped) {
                    if (code in marcref.levels.about) {
                        outjson = mergeMap(outjson, [(ABOUT_LABEL):jldMapped])
                    } else if (code in marcref.levels.instanceOf) {
                        outjson = mergeMap(outjson, [(ABOUT_LABEL):[(INSTANCE_LABEL):jldMapped]])
                    } else {
                        log.trace("top level merge of $jldMapped")
                        outjson = mergeMap(outjson, jldMapped)
                    }
                } else if (jldMapped == false) {
                    return outjson
                } else {
                    outjson = createNestedMapStructure(outjson, [RAW_LABEL,"fields"],[])
                    outjson[RAW_LABEL]["fields"] << [(code):json]
                }
                break;
        }
        return outjson
    }

    String subfieldCode(code, subfields) {
        log.trace("subfieldCode subfields: $subfields")
        def sfcode = null
        subfields.each {
            it.each { key, value ->
                if (key == code) {
                    log.trace("subfieldCode: $code = $value")
                    sfcode = value
                }
            }
        }
        log.trace("Returning $sfcode ($code, $subfields)")
        return sfcode
    }

    Map createNestedMapStructure(map, keys, type) {
        def m = map
        keys.eachWithIndex() { key, i ->
            if (i < keys.size()-1) {
                if (!m.containsKey(key)) {
                    log.trace("$key not found. creating new map.")
                    m.put(key, [:])
                    m = m.get(key)
                } else if (m[(key)] instanceof Map) {
                    m = m.get(key)
                }
            }
        }
        def lastkey = keys.get(keys.size()-1)
        log.trace("lastkey: $lastkey, m[lastkey]: " + m.get(lastkey))
        if (m.get(lastkey) != null && type instanceof List) {
            log.trace("Last item is List")
            def v = m[(keys[keys.size()-1])]
            if (!(v instanceof List)) {
                m[(keys[keys.size()-1])] = [v]
            }
        } else {
            m[(keys[keys.size()-1])] = type
        }

        return map
    }

    Map mergeMap(Map origmap, Map newmap) {
        newmap.each { key, value -> 
            if (origmap.containsKey(key)) { // Update value for original map
                if (value instanceof Map && origmap.get(key) instanceof Map) {
                    origmap[key] = mergeMap(origmap.get(key), value)
                } else {
                    if (!(origmap.get(key) instanceof List)) {
                        origmap[key] = [origmap[key]]
                    }
                    origmap[key] << value
                }
            } else { // Add key to original map
                origmap[key] = value
            }
        }
        return origmap
    }


    def createJson(URI identifier, Map injson) {
        def outjson = [:]
        def pfx = identifier.toString().split("/")[1]
        def marccracker = new MarcCrackerAndLabelerIndexFormatConverter()

        outjson["@context"] = "http://libris.kb.se/contexts/libris.jsonld"
        outjson["@id"] = identifier.toString()
        outjson["@type"] = ["Document"]
        if (whelk.getPrefix().equals("bib")) {
            outjson[ABOUT_LABEL] = ["@type":["Instance"]]
            if (injson.containsKey("leader")) {
                injson = marccracker.rewriteJson(identifier, injson)
                    log.trace("Leader: ${injson.leader}")
                    injson.leader.subfields.each { 
                        it.each { lkey, lvalue ->
                            lvalue = lvalue.trim()
                            if (lvalue && !(lvalue =~ /^\|+$/)) {
                                def lbl = marcmap.bib.fixprops?.get(lkey)?.get(lvalue)?.get("label_sv")
                                if (lkey == "typeOfRecord") {
                                    switch (lvalue) {
                                        case "a":
                                        outjson[ABOUT_LABEL]["@type"] << "Book"
                                        break;
                                        case "j":
                                        outjson[ABOUT_LABEL]["@type"] << "Music Recording"
                                        break;
                                    }
                                }
                                if (lkey in marcref.levels.instanceOf) {
                                    outjson = createNestedMapStructure(outjson, [ABOUT_LABEL, INSTANCE_LABEL], [:])
                                    outjson[ABOUT_LABEL][INSTANCE_LABEL][lkey] = ["code":lvalue,"label":(lbl ?: "")]
                                } else if (lkey in marcref.levels.about) {
                                    outjson = createNestedMapStructure(outjson, [ABOUT_LABEL], [:])
                                    outjson[ABOUT_LABEL][lkey] = ["code":lvalue,"label":(lbl ?: "")]
                                } else {
                                    outjson[lkey] = ["code":lvalue,"label":(lbl ?: "")]
                                }
                            }
                        }
                    }
            }
        }

        injson.fields.each {
            it.each { fkey, fvalue ->
                outjson = mapField(fkey, fvalue, outjson)
            }
        }
        log.trace("Constructed JSON:\n" + mapper.defaultPrettyPrintingWriter().writeValueAsString(outjson))
        return outjson
    }

    @Override
    List<Document> convert(Document idoc) {
        def outdocs = []
        if (idoc.contentType == this.requiredContentType && idoc.format == this.requiredFormat) {
            def injson = mapper.readValue(idoc.dataAsString, Map)
            outdocs << new BasicDocument(idoc).withData(mapper.writeValueAsBytes(createJson(idoc.identifier, injson))).withFormat("jsonld")
        } else {
            log.debug("This converter requires $requiredFormat in $requiredContentType. Document ${idoc.identifier} is ${idoc.format} in ${idoc.contentType}")
            outdocs << idoc
        }
        return outdocs
    }

    @Override
    List<Document> convert(List<Document> docs) {
        def outdocs = []
        for (doc in docs) {
            outdocs.addAll(convert(doc))
        }
        return outdocs
    }
}
