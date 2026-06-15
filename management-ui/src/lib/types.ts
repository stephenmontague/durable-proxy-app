// TypeScript mirrors of the proxy's JSON contracts (Jackson-serialized Java records/POJOs).
// Field names must match exactly — these cross the Temporal query/signal boundary.

export type Transport = "HTTP" | "TCP" | "FTP";
export type ChannelKind = "PATH" | "PORT" | "FOLDER";
export type Direction = "CLOUD_TO_EDGE" | "EDGE_TO_CLOUD";
export type LifecycleCommand = "NONE" | "SHUTDOWN" | "RESTART";

/** Codecs the proxy ships (com.proxyapp.control.CatalogValidator.KNOWN_CODECS). */
export type CodecName = "json" | "xml" | "raw";
export const CODECS: CodecName[] = ["json", "xml", "raw"];

/**
 * One operator-defined message type (com.proxyapp.control.CatalogEntryDto). Edited via the
 * Config page; lives in the control workflow state, no longer hardcoded in the proxy profile.
 */
export interface CatalogEntryDto {
  type: string;
  direction: Direction;
  codec: CodecName;
  cloudEndpoint?: string | null;
  businessIdField?: string | null;
  /**
   * EDGE_TO_CLOUD only. When true, identical inbound pushes are delivered individually instead of
   * deduped — for event/telemetry streams where two byte-identical frames are two real observations.
   * Default/absent = false (dedup on).
   */
  allowDuplicates?: boolean;
}

export interface Channel {
  kind: ChannelKind;
  value: string;
}

export interface ResolverConfig {
  kind: string;
  patterns?: Record<string, string>;
}

/**
 * TCP wire-protocol settings (com.proxyapp.routing.TcpProtocol). All string fields use
 * WireString escape syntax (e.g. <VT>, \x1c). Null/absent everywhere = legacy behavior.
 */
export interface TcpProtocol {
  startDelimiter?: string | null;
  endDelimiter?: string | null;
  ackReply?: string | null;
  nakReply?: string | null;
  expectedAck?: string | null;
  awaitReply?: boolean | null; // null/undefined = true (wait for the reply)
}

export type SessionMode = "PER_MESSAGE" | "PERSISTENT";
export type SessionRole = "CLIENT" | "SERVER";
export type CorrelationStrategy = "SINGLE_IN_FLIGHT" | "CORRELATION_ID" | "SEQUENCE";

/** Liveness for a persistent session (com.proxyapp.routing.TcpSession.Heartbeat). */
export interface Heartbeat {
  sendIntervalSec?: number | null;
  sendPayload?: string | null; // WireString
  expectReply?: string | null; // WireString
  replyTimeoutMs?: number | null;
  expectInboundSec?: number | null;
  missThreshold?: number | null;
}

/** Request/response matching over the shared socket (TcpSession.Correlation). */
export interface Correlation {
  strategy?: CorrelationStrategy | null;
  field?: string | null;
  delimiter?: string | null;
}

/**
 * Persistent-TCP-session config (com.proxyapp.routing.TcpSession), per device. Absent or mode
 * PER_MESSAGE = today's connect-per-message behavior; PERSISTENT keeps a heartbeated socket warm.
 * Frame delimiters reuse the device/binding tcpProtocol. See docs/persistent-tcp-sessions.md.
 */
export interface TcpSession {
  mode: SessionMode;
  role?: SessionRole | null;
  port?: number | null; // CLIENT: device port the proxy dials (host = EdgeConfig.host)
  listenPort?: number | null; // SERVER: local port the proxy listens on
  handshakeId?: string | null;
  heartbeat?: Heartbeat | null;
  correlation?: Correlation | null;
  /** Single message type for unsolicited device→cloud frames (an EDGE_TO_CLOUD type). */
  inboundType?: string | null;
  /** Or, for a socket carrying several inbound types: a content resolver. Mutually exclusive. */
  resolver?: ResolverConfig | null;
}

export interface RouteBinding {
  messageType: string | null;
  transport: Transport;
  channel: Channel;
  resolver?: ResolverConfig | null;
  tcpProtocol?: TcpProtocol | null;
}

export interface EdgeConfig {
  deviceId: string;
  baseUrl?: string | null;
  host?: string | null;
  ftpPort?: number | null;
  ftpUser?: string | null;
  ftpPassword?: string | null;
  bindings: RouteBinding[];
  tcpProtocol?: TcpProtocol | null;
  tcpSession?: TcpSession | null;
}

export type SessionState = "CONNECTING" | "UP" | "DOWN";

/** Per-device persistent-link health (com.proxyapp.session.DeviceSessionStatus). */
export interface DeviceSessionStatus {
  deviceId: string;
  role: SessionRole;
  state: SessionState;
  lastHeartbeatAt?: string | null;
  inflight: number;
}

/** What the proxy reports back after each reconcile (com.proxyapp.control.AppliedStatus). */
export interface AppliedStatus {
  version: number;
  enabled: boolean;
  httpPaths: string[];
  tcpPorts: number[];
  ftpFolders: string[];
  startedAt: string;
  reportedAt: string;
  /** False = nothing will relaunch the proxy after RESTART (it acts like SHUTDOWN). */
  supervised?: boolean;
  /** Per-device persistent-link health; empty/absent when no device uses a persistent session. */
  sessions?: DeviceSessionStatus[] | null;
}

/** The control workflow's queryable state (com.proxyapp.control.ProxyControlState). */
export interface ProxyControlState {
  enabled: boolean;
  devices: EdgeConfig[];
  version: number;
  lastError: string | null;
  typeDirections: Record<string, Direction>;
  /** Full catalog (Part 3). Null on installs that predate it — proxy uses its profile catalog. */
  catalogEntries?: CatalogEntryDto[] | null;
  tcpPortPool: number[];
  lifecycleCommand?: LifecycleCommand;
  lifecycleRequestId?: string | null;
  applied?: AppliedStatus | null;
}

/** Worker liveness inferred from Temporal task queue pollers (DescribeTaskQueue). */
export interface WorkerLiveness {
  controlPollers: number;
  dataPollers: number;
  lastAccessAgoMs: number | null;
}

export interface ControlStateResponse {
  state: ProxyControlState;
  liveness: WorkerLiveness;
}

/** One row in the activity feed — a DeliverToEdge workflow or DeliverToCloud activity. */
export interface FeedItem {
  id: string;
  kind: "workflow" | "activity";
  type: string;
  direction: "CLOUD_TO_EDGE" | "EDGE_TO_CLOUD";
  status: string;
  startTime: string | null;
  closeTime: string | null;
  durationMs: number | null;
  runId?: string;
}
