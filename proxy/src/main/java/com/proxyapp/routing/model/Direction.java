package com.proxyapp.routing.model;

/** Direction of a message flow, from the proxy's point of view. */
public enum Direction {
    /** Outbound: the cloud app has something for the edge target. */
    CLOUD_TO_EDGE,
    /** Inbound: the edge target has something for the cloud app. */
    EDGE_TO_CLOUD
}
