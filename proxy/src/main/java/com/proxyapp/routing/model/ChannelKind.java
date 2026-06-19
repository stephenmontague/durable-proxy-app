package com.proxyapp.routing.model;

/** How a transport expresses "channel". One inbound channel carries exactly one message type. */
public enum ChannelKind {
    /** TCP listen port (inbound) or remote port (outbound). */
    PORT,
    /** HTTP path (inbound) or path appended to the device baseUrl (outbound). */
    PATH,
    /** FTP folder watched for drops (inbound) or remote folder (outbound). */
    FOLDER
}
