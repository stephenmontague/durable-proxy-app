package com.proxyapp.session.model;

/** Liveness of a persistent device link, surfaced per device in the UI's connection table. */
public enum DeviceSessionState {
    /** Dialing the device (CLIENT) and not yet usable. */
    CONNECTING,
    /** Socket open and within its heartbeat liveness window. */
    UP,
    /** Disconnected, or too many missed heartbeats; a reconnect is in progress. */
    DOWN
}
