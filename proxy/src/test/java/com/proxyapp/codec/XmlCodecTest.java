package com.proxyapp.codec;

import com.proxyapp.model.CanonicalMessage;
import com.proxyapp.routing.CatalogEntry;
import com.proxyapp.routing.Direction;
import com.proxyapp.routing.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class XmlCodecTest {

    private final XmlCodec codec = new XmlCodec();
    private final CatalogEntry entry = new CatalogEntry(MessageType.of("CONFIG_ACK"),
            Direction.EDGE_TO_CLOUD, "xml", "/api/config-ack", "configId");

    @Test
    void encodeSendsPayloadAsIs() {
        String xml = "<confirm><configId>C-1</configId></confirm>";
        CanonicalMessage message = new CanonicalMessage("CONFIG_ACK", "C-1", xml);
        assertThat(codec.encode(message)).isEqualTo(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodeExtractsBusinessIdFromConfiguredElement() {
        byte[] raw = "<confirm><configId>C-42</configId><status>OK</status></confirm>"
                .getBytes(StandardCharsets.UTF_8);
        CanonicalMessage message = codec.decode(entry, raw);
        assertThat(message.messageType()).isEqualTo("CONFIG_ACK");
        assertThat(message.businessId()).isEqualTo("C-42");
        assertThat(message.activityId()).isEqualTo("CONFIG_ACK-C-42");
    }

    @Test
    void decodeFindsNestedElement() {
        byte[] raw = "<msg><header><configId>C-7</configId></header></msg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(codec.decode(entry, raw).businessId()).isEqualTo("C-7");
    }

    @Test
    void decodeFallsBackToContentHashWhenElementMissing() {
        byte[] raw = "<confirm><other>x</other></confirm>".getBytes(StandardCharsets.UTF_8);
        CanonicalMessage first = codec.decode(entry, raw);
        CanonicalMessage second = codec.decode(entry, raw);
        assertThat(first.businessId()).isNotBlank().isEqualTo(second.businessId());
        assertThat(first.businessId()).isNotEqualTo("C-42");
    }

    @Test
    void decodeFallsBackToContentHashOnMalformedXml() {
        byte[] raw = "not xml at all <<<".getBytes(StandardCharsets.UTF_8);
        assertThat(codec.decode(entry, raw).businessId()).isNotBlank();
    }

    @Test
    void decodeIsXxeHardenedAndFallsBackInsteadOfResolvingEntities() {
        // A DOCTYPE with an external entity must not be expanded; the parser rejects the
        // doctype, decode swallows it, and we get a content-hash id (no file read, no crash).
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<confirm><configId>&xxe;</configId></confirm>";
        CanonicalMessage message = codec.decode(entry, xxe.getBytes(StandardCharsets.UTF_8));
        assertThat(message.businessId()).isNotBlank().doesNotContain("root:");
    }
}
