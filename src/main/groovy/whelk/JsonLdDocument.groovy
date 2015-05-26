package whelk

import groovy.util.logging.Slf4j as Log

@Log
class JsonLdDocument extends JsonDocument {


    final static String ID_KEY = "@id"

    protected List<String> findLinks(Map dataMap) {
        Set<String> ids = new HashSet<String>()
        for (entry in dataMap) {
            if (entry.key == ID_KEY && ![identifier, thingId].contains(entry.value)) {
                ids.add(entry.value)
            } else if (entry.value instanceof Map) {
                ids.addAll(findLinks(entry.value))
            } else if (entry.value instanceof List) {
                for (l in entry.value) {
                    if (l instanceof Map) {
                        ids.addAll(findLinks(l))
                    }
                }
            }
        }
        return ids as List
    }

    public String getThingId() {
        return "/resource"+identifier
    }

    void setData(byte[] data) {
        super.setData(data)
        def links = findLinks(getDataAsMap())
        log.info("For ${identifier}, found links: ${links}")
        if (links) {
            this.entry['links'] = links
        }
    }


}
