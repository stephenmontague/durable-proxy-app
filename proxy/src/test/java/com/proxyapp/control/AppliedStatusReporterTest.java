package com.proxyapp.control;

import com.proxyapp.control.model.AppliedStatus;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.profile.DeviceFleetProfile;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.session.TcpSessionManager;
import com.proxyapp.session.model.DeviceSessionStatus;
import com.proxyapp.temporal.workflow.ProxyControlWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reporter must cost zero Temporal Actions when nothing changed, so what's worth locking is
 * when it stays silent. Tests drive {@code reportIfChanged()} directly rather than the scheduler.
 */
class AppliedStatusReporterTest {

    private WorkflowClient workflowClient;
    private ProxyControlWorkflow controlStub;
    private RoutingState routingState;
    private TcpSessionManager sessionManager;
    private AppliedStatusReporter reporter;

    @BeforeEach
    void setUp() {
        workflowClient = mock(WorkflowClient.class);
        controlStub = mock(ProxyControlWorkflow.class);
        when(workflowClient.newWorkflowStub(any(Class.class), anyString())).thenReturn(controlStub);

        MessageCatalog catalog = new DeviceFleetProfile().catalog();
        routingState = new RoutingState(catalog);

        TcpSocketServer tcpSocketServer = mock(TcpSocketServer.class);
        when(tcpSocketServer.activePorts()).thenReturn(Set.of());
        FtpIngressListener ftpIngressListener = mock(FtpIngressListener.class);
        when(ftpIngressListener.activeFolders()).thenReturn(Set.of());
        sessionManager = mock(TcpSessionManager.class);
        when(sessionManager.statuses()).thenReturn(List.of());

        reporter = new AppliedStatusReporter(workflowClient, routingState, tcpSocketServer,
                ftpIngressListener, sessionManager);
    }

    /** Put the proxy into "v{version} applied and enabled" without running a real reconcile. */
    private void applied(long version) {
        routingState.update(RouteTable.empty(new DeviceFleetProfile().catalog()), true, version);
    }

    private void deviceIs(String state, String lastError) {
        when(sessionManager.statuses()).thenReturn(List.of(new DeviceSessionStatus(
                "gateway-1", "CLIENT", state, null, 0, lastError, null, List.of())));
    }

    @Test
    void staysSilentUntilSomethingHasActuallyBeenApplied() {
        // appliedVersion is -1 on a fresh proxy: reporting then would publish a meaningless v-1.
        reporter.reportIfChanged();
        verify(controlStub, never()).reportApplied(any());
    }

    @Test
    void reportsOnceThenGoesQuietWhileNothingChanges() {
        applied(4);
        when(controlStub.reportApplied(any())).thenReturn(4L);

        reporter.reportIfChanged();
        reporter.reportIfChanged();
        reporter.reportIfChanged();

        // Three ticks, one Action — this is the property the whole dedupe baseline exists for.
        verify(controlStub, times(1)).reportApplied(any());
    }

    @Test
    void reportsAgainWhenALinkChangesState() {
        applied(4);
        when(controlStub.reportApplied(any())).thenReturn(4L);
        deviceIs("UP", null);
        reporter.reportIfChanged();

        deviceIs("DOWN", "connection refused");
        reporter.reportIfChanged();

        verify(controlStub, times(2)).reportApplied(any());
    }

    @Test
    void reportsAgainWhenOnlyTheDropReasonChanges() {
        applied(4);
        when(controlStub.reportApplied(any())).thenReturn(4L);
        deviceIs("DOWN", "connection refused");
        reporter.reportIfChanged();

        // Same state, different why — the reason is the diagnostic that has to reach the cloud.
        deviceIs("DOWN", "link down after 3 missed heartbeat(s)");
        reporter.reportIfChanged();

        verify(controlStub, times(2)).reportApplied(any());
    }

    @Test
    void requestsAReconcileWhenTheProxyIsBehindDesiredState() {
        applied(4);
        when(controlStub.reportApplied(any())).thenReturn(7L); // cloud wants v7, we have v4

        reporter.reportIfChanged();

        verify(controlStub).requestReconcile();
    }

    @Test
    void doesNotRequestAReconcileWhenAlreadyAtDesiredVersion() {
        applied(7);
        when(controlStub.reportApplied(any())).thenReturn(7L);

        reporter.reportIfChanged();

        verify(controlStub, never()).requestReconcile();
    }

    @Test
    void syncBaselineSuppressesTheRedundantPostReconcileReport() {
        applied(4);
        // The reconcile activity already returned this to the workflow; re-reporting duplicates it.
        reporter.syncBaseline(reporter.snapshot());

        reporter.reportIfChanged();

        verify(controlStub, never()).reportApplied(any());
    }

    @Test
    void aFailedReportIsNotTreatedAsDeliveredAndRetriesOnTheNextTick() {
        applied(4);
        when(controlStub.reportApplied(any()))
                .thenThrow(new IllegalStateException("workflow not started yet"))
                .thenReturn(4L);

        reporter.reportIfChanged(); // throws internally, must not advance the baseline
        reporter.reportIfChanged();

        verify(controlStub, times(2)).reportApplied(any());
    }

    @Test
    void snapshotReflectsLiveRoutingState() {
        applied(9);
        AppliedStatus snapshot = reporter.snapshot();
        assertThat(snapshot.version()).isEqualTo(9);
        assertThat(snapshot.enabled()).isTrue();
    }
}
