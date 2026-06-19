package com.proxyapp.session;

/**
 * A persistent-session send could not be completed (link down, slot busy, write error, or no ack
 * in time). Thrown to the outbound activity, where it fails the activity so Temporal retries —
 * the message waits durably and delivers when the link is back, exactly like a per-message send.
 */
public class SessionSendException extends RuntimeException {

    public SessionSendException(String message) {
        super(message);
    }
}
