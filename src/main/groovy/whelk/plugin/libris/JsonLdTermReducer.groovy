package whelk.plugin.libris

import groovy.util.logging.Slf4j as Log

import whelk.Document
import whelk.plugin.BasicFilter

@Log
class JsonLdTermReducer extends BasicFilter {

    JsonLdTermReducer() {
    }

    boolean valid(Document doc) {
        return doc && doc.isJson() && doc.contentType == "application/ld+json"
    }

    Document doFilter(Document doc) {
        def dataMap = doFilter(doc.dataAsMap, doc.dataset)
        return doc.withData(dataMap)
    }

    Map doFilter(Map dataMap, String dataset) {
        def thing = dataMap.about ?: dataMap.focus ?: dataMap
        reduce(thing)
        return dataMap
    }

    void reduce(thing) {

        add thing, 'title', thing.instanceTitle?.titleValue

        add thing, 'isbn', thing.identifier.findAll {
            it.identifierScheme == "/def/identifiers/isbn"
        }.collect {
            it.identifierValue
        }

        add thing, 'creator', ['attributedTo', 'influencedBy'].collect {
            asList(thing[it])
        }.flatten().findAll()

    }

    void add(owner, term, value) {
        if (value) {
            def container = owner[term]
            if (container instanceof List) {
                container += value
            } else {
                owner[term] = value
            }
        }
    }

    List asList(value) {
        return value instanceof List? value : [value]
    }
}