package com.proxyapp.control;

import com.proxyapp.config.ProxyProperties;
import com.proxyapp.control.model.CatalogEntryDto;
import com.proxyapp.control.model.ProxyControlState;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.profile.DeviceFleetProfile;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.routing.model.Channel;
import com.proxyapp.routing.model.EdgeConfig;
import com.proxyapp.routing.model.RouteBinding;
import com.proxyapp.routing.model.Transport;
import com.proxyapp.session.TcpSessionManager;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Reconciler is what actually makes desired state live, so these run it against <b>real</b>
 * ingress collaborators (which bind real ports) rather than nulls — a reconcile that "succeeds"
 * without opening a listener is the failure mode worth catching. Only the Temporal
 * {@link WorkerFactory} is mocked.
 *
 * <p>Ports come from the pool below and are released by {@link #tearDown()}.
 */
class ReconcilerTest {

    private static final String TASK_QUEUE = "proxy-main";
    /** Inside the configured pool, and clear of the ports the other socket tests use. */
    private static final int TCP_PORT = 6007;

    private MessageCatalog catalog;
    private RoutingState routingState;
    private TcpSocketServer tcpSocketServer;
    private FtpIngressListener ftpIngressListener;
    private TcpSessionManager tcpSessionManager;
    private Worker dataWorker;
    private Reconciler reconciler;

    @BeforeEach
    void setUp() {
        catalog = new DeviceFleetProfile().catalog();
        routingState = new RoutingState(catalog);

        ProxyProperties properties = new ProxyProperties(TASK_QUEUE, "proxy-control", "device-fleet",
                null, new ProxyProperties.Ingress("6000-6010", 0, "./target/test-ftp", "u", "p"), null);

        tcpSocketServer = new TcpSocketServer(gateway());
        ftpIngressListener = new FtpIngressListener(null, properties);
        tcpSessionManager = new TcpSessionManager((cfg, frame) -> { });

        dataWorker = mock(Worker.class);
        WorkerFactory workerFactory = mock(WorkerFactory.class);
        when(workerFactory.getWorker(anyString())).thenReturn(dataWorker);

        reconciler = new Reconciler(properties, catalog, routingState, tcpSocketServer,
                ftpIngressListener, tcpSessionManager, workerFactory);
    }

    @AfterEach
    void tearDown() {
        tcpSocketServer.shutdown();
        tcpSessionManager.shutdown();
        ftpIngressListener.shutdown();
    }

    /** No inbound traffic is driven in these tests, so the sink is never invoked. */
    private static com.proxyapp.ingress.InboundSink gateway() {
        return (transport, channel, filename, raw) -> {
            throw new AssertionError("no inbound traffic expected in ReconcilerTest");
        };
    }

    private static ProxyControlState desired(long version, boolean enabled, List<EdgeConfig> devices) {
        ProxyControlState state = new ProxyControlState();
        state.setVersion(version);
        state.setEnabled(enabled);
        state.setDevices(new ArrayList<>(devices));
        return state;
    }

    /** One device with a single inbound TCP binding on {@link #TCP_PORT}. */
    private static EdgeConfig deviceWithTcpIngress() {
        return new EdgeConfig("gateway-1", "http://edge:8082", "10.0.0.5", null, null, null,
                List.of(new RouteBinding(DeviceFleetProfile.CONFIG_ACK, Transport.TCP,
                        Channel.port(TCP_PORT))));
    }

    @Test
    void enabledStateOpensTheConfiguredIngressAndResumesTheDataWorker() {
        when(dataWorker.isSuspended()).thenReturn(true);

        reconciler.apply(desired(1, true, List.of(deviceWithTcpIngress())));

        assertThat(tcpSocketServer.activePorts()).containsExactly(TCP_PORT);
        assertThat(routingState.appliedVersion()).isEqualTo(1);
        assertThat(routingState.enabled()).isTrue();
        verify(dataWorker).resumePolling();
    }

    @Test
    void disabledStateClosesIngressAndSuspendsTheDataWorker() {
        when(dataWorker.isSuspended()).thenReturn(true);
        reconciler.apply(desired(1, true, List.of(deviceWithTcpIngress())));
        assertThat(tcpSocketServer.activePorts()).containsExactly(TCP_PORT);

        // Soft disable: listeners go away, but the route table is still applied.
        when(dataWorker.isSuspended()).thenReturn(false);
        reconciler.apply(desired(2, false, List.of(deviceWithTcpIngress())));

        assertThat(tcpSocketServer.activePorts()).isEmpty();
        assertThat(routingState.enabled()).isFalse();
        assertThat(routingState.appliedVersion()).isEqualTo(2);
        verify(dataWorker).suspendPolling();
    }

    @Test
    void staleControlStateIsIgnoredAndNeverRegressesAppliedVersion() {
        routingState.update(RouteTable.empty(catalog), true, 5); // pretend v5 is live

        reconciler.apply(desired(3, true, List.of(deviceWithTcpIngress())));

        // The monotonic guard runs before any collaborator is touched.
        assertThat(routingState.appliedVersion()).isEqualTo(5);
        assertThat(tcpSocketServer.activePorts()).isEmpty();
        verify(dataWorker, never()).resumePolling();
    }

    @Test
    void invalidConfigIsRefusedAndTheLastGoodStateStaysLive() {
        when(dataWorker.isSuspended()).thenReturn(true);
        reconciler.apply(desired(1, true, List.of(deviceWithTcpIngress())));

        // Port 9999 is outside the 6000-6010 pool, so validation rejects the whole push.
        EdgeConfig outsidePool = new EdgeConfig("gateway-1", "http://edge:8082", "10.0.0.5",
                null, null, null, List.of(new RouteBinding(DeviceFleetProfile.CONFIG_ACK,
                        Transport.TCP, Channel.port(9999))));
        reconciler.apply(desired(2, true, List.of(outsidePool)));

        assertThat(routingState.appliedVersion()).isEqualTo(1);   // never advanced
        assertThat(tcpSocketServer.activePorts()).containsExactly(TCP_PORT); // still the good config
    }

    @Test
    void catalogFallsBackToTheBootProfileWhenControlStateCarriesNone() {
        when(dataWorker.isSuspended()).thenReturn(true);
        ProxyControlState state = desired(1, true, List.of(deviceWithTcpIngress()));
        state.setCatalogEntries(null); // a workflow that predates the editable catalog

        reconciler.apply(state);

        // CONFIG_ACK only resolves via the boot profile catalog, so a successful apply proves the
        // fallback ran rather than the reconcile bailing out.
        assertThat(routingState.appliedVersion()).isEqualTo(1);
        assertThat(tcpSocketServer.activePorts()).containsExactly(TCP_PORT);
    }

    @Test
    void operatorEditedCatalogReplacesTheBootProfileCatalog() {
        when(dataWorker.isSuspended()).thenReturn(true);
        ProxyControlState state = desired(1, true, List.of(deviceWithTcpIngress()));
        state.setCatalogEntries(List.of(
                new CatalogEntryDto("CONFIG_ACK", "EDGE_TO_CLOUD", "xml", "/api/config-ack", "configId")));

        reconciler.apply(state);

        assertThat(routingState.appliedVersion()).isEqualTo(1);
        assertThat(routingState.table().catalog()
                .require(DeviceFleetProfile.CONFIG_ACK).codec()).isEqualTo("xml");
    }

    @Test
    void aMissingDataWorkerDoesNotAbortTheReconcile() {
        WorkerFactory noWorker = mock(WorkerFactory.class);
        when(noWorker.getWorker(anyString()))
                .thenThrow(new IllegalStateException("worker not registered yet"));
        ProxyProperties properties = new ProxyProperties(TASK_QUEUE, "proxy-control", "device-fleet",
                null, new ProxyProperties.Ingress("6000-6010", 0, "./target/test-ftp", "u", "p"), null);
        Reconciler withoutWorker = new Reconciler(properties, catalog, routingState, tcpSocketServer,
                ftpIngressListener, tcpSessionManager, noWorker);

        withoutWorker.apply(desired(1, true, List.of(deviceWithTcpIngress())));

        // Ingress still came up; only the polling toggle was skipped.
        assertThat(routingState.appliedVersion()).isEqualTo(1);
        assertThat(tcpSocketServer.activePorts()).containsExactly(TCP_PORT);
    }
}
