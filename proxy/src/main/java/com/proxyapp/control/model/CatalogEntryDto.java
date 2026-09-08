package com.proxyapp.control.model;

import com.proxyapp.routing.model.CatalogEntry;
import com.proxyapp.routing.model.Direction;
import com.proxyapp.routing.model.MessageType;

/**
 * The wire shape of a {@link CatalogEntry}: a flat, Jackson-friendly mirror using plain strings
 * (no {@link MessageType} wrapper or {@link Direction} enum) so it serializes cleanly into workflow
 * state and across the update/query boundary a control client talks to. {@code direction} is a
 * {@code Direction} name; {@code codec} is {@code "json"}, {@code "xml"}, or {@code "raw"}.
 */
public record CatalogEntryDto(String type, String direction, String codec,
                              String cloudEndpoint, String businessIdField, boolean allowDuplicates) {

    /** Default: dedup on. Also the shape stored state deserializes into. */
    public CatalogEntryDto(String type, String direction, String codec,
                           String cloudEndpoint, String businessIdField) {
        this(type, direction, codec, cloudEndpoint, businessIdField, false);
    }

    /** Flatten a catalog entry for transport in workflow state. */
    public static CatalogEntryDto from(CatalogEntry entry) {
        return new CatalogEntryDto(entry.type().value(), entry.direction().name(),
                entry.codec(), entry.cloudEndpoint(), entry.businessIdField(), entry.allowDuplicates());
    }

    /** Rehydrate into a {@link CatalogEntry}; assumes CatalogValidator already accepted it, so
     *  {@code direction} is a valid enum constant. */
    public CatalogEntry toCatalogEntry() {
        return new CatalogEntry(MessageType.of(type), Direction.valueOf(direction),
                codec, cloudEndpoint, businessIdField, allowDuplicates);
    }
}
