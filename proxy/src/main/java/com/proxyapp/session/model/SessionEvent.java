package com.proxyapp.session.model;

/**
 * One persistent-link state transition, kept in a small per-session ring buffer inside
 * {@link DeviceSessionStatus#recentEvents()} so a short history rides the egress connection back to
 * the control workflow. This is what lets an operator see <i>why</i> a link flapped without ever
 * reaching the proxy's local logs. Mirrored in the management UI's types.ts.
 *
 * @param at     ISO-8601 timestamp of the transition (proxy clock)
 * @param state  the state entered: CONNECTING / UP / DOWN ({@link DeviceSessionState} name)
 * @param detail human-readable reason/context, e.g. "connection refused" or "link up (/10.0.0.5:9100)"
 */
public record SessionEvent(String at, String state, String detail) {
}
