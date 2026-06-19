package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.model.CatalogEntry;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Codec for edge targets that speak XML (ASN documents, HL7-style payloads, etc.). Like
 * {@link JsonCodec} the proxy doesn't transform the payload — encoding sends it as-is — the only
 * job on decode is pulling the dedup business id out of the configured element, falling back to a
 * content hash when the element is absent or the body isn't parseable.
 */
public class XmlCodec implements MessageCodec {

    public static final String NAME = "xml";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public byte[] encode(CanonicalMessage message) {
        return message.payload().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalMessage decode(CatalogEntry entry, byte[] raw) {
        String payload = new String(raw, StandardCharsets.UTF_8).trim();
        String businessId = null;
        if (entry.businessIdField() != null) {
            businessId = firstElementText(payload, entry.businessIdField());
        }
        if (businessId == null || businessId.isBlank()) {
            businessId = ContentHash.of(payload);
        }
        return new CanonicalMessage(entry.type().value(), businessId, payload);
    }

    /** Text of the first {@code <elementName>} anywhere in the document, or null if absent/unparseable. */
    private static String firstElementText(String xml, String elementName) {
        try {
            Document doc = newSafeBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = doc.getElementsByTagName(elementName);
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                return text == null ? null : text.trim();
            }
        } catch (Exception e) {
            // not well-formed XML, or the element isn't present — fall through to the content hash
        }
        return null;
    }

    /** A DocumentBuilder with external entities / DOCTYPE disabled (XXE-hardened). */
    private static DocumentBuilder newSafeBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
