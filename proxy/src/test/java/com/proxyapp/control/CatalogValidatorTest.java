package com.proxyapp.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogValidatorTest {

    @Test
    void validEntryHasNoErrors() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("COMMAND_RESULT", "EDGE_TO_CLOUD", "json", "/api/command-result", "commandId"),
                CatalogValidator.KNOWN_CODECS)).isEmpty();
    }

    @Test
    void cloudToEdgeNeedsNoCloudEndpoint() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("DEVICE_COMMAND", "CLOUD_TO_EDGE", "json", null, "commandId"),
                CatalogValidator.KNOWN_CODECS)).isEmpty();
    }

    @Test
    void blankTypeIsRejected() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("  ", "CLOUD_TO_EDGE", "json", null, null),
                CatalogValidator.KNOWN_CODECS))
                .contains("message type name must not be blank");
    }

    @Test
    void unknownDirectionIsRejected() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("X", "SIDEWAYS", "json", null, null),
                CatalogValidator.KNOWN_CODECS))
                .anyMatch(e -> e.contains("unknown direction 'SIDEWAYS'"));
    }

    @Test
    void unknownCodecIsRejected() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("X", "CLOUD_TO_EDGE", "yaml", null, null),
                CatalogValidator.KNOWN_CODECS))
                .anyMatch(e -> e.contains("unknown codec 'yaml', available: [json, raw, xml]"));
    }

    @Test
    void edgeToCloudWithoutCloudEndpointIsRejected() {
        assertThat(CatalogValidator.validateEntry(
                new CatalogEntryDto("X", "EDGE_TO_CLOUD", "json", "  ", null),
                CatalogValidator.KNOWN_CODECS))
                .contains("message type X: EDGE_TO_CLOUD type requires a cloudEndpoint");
    }

    @Test
    void emptyCatalogIsRejected() {
        assertThat(CatalogValidator.validateCatalog(List.of(), CatalogValidator.KNOWN_CODECS))
                .contains("catalog must define at least one message type");
    }

    @Test
    void duplicateTypesAreRejected() {
        assertThat(CatalogValidator.validateCatalog(List.of(
                        new CatalogEntryDto("DUP", "CLOUD_TO_EDGE", "json", null, null),
                        new CatalogEntryDto("DUP", "CLOUD_TO_EDGE", "json", null, null)),
                CatalogValidator.KNOWN_CODECS))
                .contains("duplicate message type: DUP");
    }
}
