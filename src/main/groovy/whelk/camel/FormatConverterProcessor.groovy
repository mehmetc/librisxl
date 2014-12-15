package whelk.camel

import groovy.util.logging.Slf4j as Log

import whelk.Document
import whelk.plugin.*
import whelk.component.*
import whelk.*

import org.apache.camel.processor.UnmarshalProcessor
import org.apache.camel.spi.DataFormat
import org.apache.camel.Exchange
import org.apache.camel.Message
import org.apache.camel.Processor
import org.apache.camel.component.jackson.JacksonDataFormat

import org.codehaus.jackson.map.ObjectMapper

@Log
class FormatConverterProcessor extends BasicPlugin implements Processor,WhelkAware {

    // Maybe rename to DocumentConverterProcessor

    FormatConverter converter
    Filter expander

    static final ObjectMapper mapper = new ObjectMapper()

    void bootstrap(String whelkName) {
        this.converter = plugins.find { it instanceof FormatConverter  }
        this.expander = plugins.find { it instanceof Filter }
        log.info("Calling bootstrap for ${this.id}. converter: $converter expander: $expander plugins: $plugins")
    }

    Document createDocument(Message docMessage) {
        def body = docMessage.getBody()
        Document doc
        if (body instanceof String) {
            doc = whelk.get(docMessage.getBody())
            log.debug("Loaded document ${doc?.identifier}")
        } else {
            log.debug("Setting document data with type ${body.getClass().getName()}")
            doc = whelk.createDocument(docMessage.getHeader("entry:contentType")).withData(body)
            docMessage.headers.each { key, value ->
                if (key.startsWith("entry:")) {
                    log.debug("Setting entry $key = $value")
                    doc.entry.put(key.substring(6), value)
                }
                if (key.startsWith("meta:")) {
                    log.debug("Setting meta $key = $value")
                    doc.meta.put(key.substring(5), value)
                }
            }
        }
        return doc
    }

    Document runConverters(Document doc) {
        log.debug("converter: $converter expander: $expander")
        if (doc && (converter || expander)) {
            if (converter) {
                log.debug("Running converter ${converter.id}.")
                doc = converter.convert(doc)
            }
            if (expander) {
                log.debug("Running expander ${expander.id}.")
                doc = expander.filter(doc)
            }
        }
        return doc
    }

    void prepareMessage(Document doc, Message docMessage) {
        log.debug("Resetting document ${doc.identifier} in message.")
        docMessage.setBody(doc.data)
        doc.entry.each { key, value ->
            docMessage.setHeader("entry:$key", value)
        }
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message message = exchange.getIn()
        log.debug("Received message to ${this.id}.")
        log.debug("Message type: ${message.getHeader('whelk:operation')}")
        log.debug("Dataset: ${message.getHeader('entry:dataset')}")
        if (message.getHeader("whelk:operation") == Whelk.REMOVE_OPERATION) {
            message.setHeader("entry:identifier", message.body)
            message.setHeader("entry:dataset", message.getHeader("whelk:dataset"))
        } else {
            def doc = createDocument(message)
            doc = runConverters(doc)
            prepareMessage(doc, message)
            exchange.setOut(message)
        }
    }
}