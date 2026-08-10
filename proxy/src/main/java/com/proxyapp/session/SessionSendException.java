package com.proxyapp.session;

/**
 * A persistent-session send could not be completed (link down, slot busy, write error, no ack in time).
 * Fails the outbound activity so Temporal retries, leaving the message durable until the link is back.
 */
public class SessionSendException extends RuntimeException {

    public SessionSendException(String message) {
        super(message);
    }
}
