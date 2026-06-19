package com.proxyapp.control;
import com.proxyapp.control.model.CatalogEntryDto;

import com.proxyapp.routing.model.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates proposed message-catalog changes. Pure and deterministic so it can run inside the
 * control workflow's signal handlers (no I/O). Mirrored verbatim in the management UI's
 * validate.ts — message text must stay identical so the UI shows the same errors the workflow
 * would reject with.
 */
public final class CatalogValidator {

    /** Codecs the proxy ships; a catalog entry may name only one of these. */
    public static final Set<String> KNOWN_CODECS = Set.of("json", "xml", "raw");

    private CatalogValidator() {
    }

    /** Validate a single message-type entry (used by {@code upsertMessageType}). */
    public static List<String> validateEntry(CatalogEntryDto entry, Set<String> knownCodecs) {
        List<String> errors = new ArrayList<>();
        if (entry == null) {
            errors.add("message type entry must not be null");
            return errors;
        }
        String label = entry.type() == null || entry.type().isBlank()
                ? "message type" : "message type " + entry.type();

        if (entry.type() == null || entry.type().isBlank()) {
            errors.add("message type name must not be blank");
        }

        boolean edgeToCloud = false;
        if (entry.direction() == null || entry.direction().isBlank()) {
            errors.add(label + ": direction must not be blank");
        } else {
            try {
                edgeToCloud = Direction.valueOf(entry.direction()) == Direction.EDGE_TO_CLOUD;
            } catch (IllegalArgumentException e) {
                errors.add(label + ": unknown direction '" + entry.direction()
                        + "' (expected CLOUD_TO_EDGE or EDGE_TO_CLOUD)");
            }
        }

        if (entry.codec() == null || entry.codec().isBlank()) {
            errors.add(label + ": codec must not be blank");
        } else if (!knownCodecs.contains(entry.codec())) {
            errors.add(label + ": unknown codec '" + entry.codec() + "', available: "
                    + sorted(knownCodecs));
        }

        if (edgeToCloud && (entry.cloudEndpoint() == null || entry.cloudEndpoint().isBlank())) {
            errors.add(label + ": EDGE_TO_CLOUD type requires a cloudEndpoint");
        }
        return errors;
    }

    /** Validate a whole catalog (used by {@code importCatalog}): per-entry rules + no duplicates. */
    public static List<String> validateCatalog(List<CatalogEntryDto> entries,
                                                Set<String> knownCodecs) {
        List<String> errors = new ArrayList<>();
        if (entries == null) {
            errors.add("catalog must not be null");
            return errors;
        }
        if (entries.isEmpty()) {
            errors.add("catalog must define at least one message type");
            return errors;
        }
        Set<String> seen = new HashSet<>();
        for (CatalogEntryDto entry : entries) {
            errors.addAll(validateEntry(entry, knownCodecs));
            if (entry != null && entry.type() != null && !entry.type().isBlank()
                    && !seen.add(entry.type())) {
                errors.add("duplicate message type: " + entry.type());
            }
        }
        return errors;
    }

    private static List<String> sorted(Set<String> codecs) {
        return codecs.stream().sorted().toList();
    }
}
