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
