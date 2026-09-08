package com.proxyapp.session.model;

/**
 * One persistent-link state transition, kept in the ring buffer at
 * {@link DeviceSessionStatus#recentEvents()} so a short flap history reaches the control workflow.
 * {@code at} is ISO-8601 on the proxy clock; {@code state} is a {@link DeviceSessionState} name.
 */
public record SessionEvent(String at, String state, String detail) {
}
