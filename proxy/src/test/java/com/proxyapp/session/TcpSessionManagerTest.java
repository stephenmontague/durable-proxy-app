package com.proxyapp.session;

import com.proxyapp.routing.TcpSession;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reconcile add/remove/update of the connection table, and the statuses() projection. */
class TcpSessionManagerTest {

    @Test
    void reconcileOpensThenClosesSessions() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            TcpSessionManager manager = new TcpSessionManager(500, 50, 200);
            try {
                manager.reconcile(List.of(cfg("dev-1", server.port())));
                awaitSessionState(manager, "dev-1", "UP", 2_000);
                assertThat(manager.activeDeviceIds()).containsExactly("dev-1");

                manager.reconcile(List.of());
                assertThat(manager.activeDeviceIds()).isEmpty();
                assertThat(manager.statuses()).isEmpty();
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void reconcileReopensWhenConfigChanges() throws Exception {
        try (StubTcpServer first = new StubTcpServer(StubTcpServer::silent);
             StubTcpServer second = new StubTcpServer(StubTcpServer::silent)) {
            TcpSessionManager manager = new TcpSessionManager(500, 50, 200);
            try {
                manager.reconcile(List.of(cfg("dev-1", first.port())));
                awaitTrue(() -> !first.accepted.isEmpty(), 2_000);

                // same device, different endpoint -> the session must be reopened against the new port
                manager.reconcile(List.of(cfg("dev-1", second.port())));
                awaitTrue(() -> !second.accepted.isEmpty(), 2_000);
                awaitSessionState(manager, "dev-1", "UP", 2_000);
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void reconcileLeavesUnchangedSessionsAlone() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            TcpSessionManager manager = new TcpSessionManager(500, 50, 200);
            try {
                manager.reconcile(List.of(cfg("dev-1", server.port())));
                awaitTrue(() -> server.accepted.size() == 1, 2_000);

                // identical config -> no reopen, so no new connection is dialed
                manager.reconcile(List.of(cfg("dev-1", server.port())));
                Thread.sleep(300);
                assertThat(server.accepted).hasSize(1);
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void statusesListEveryDeviceSortedById() throws Exception {
        try (StubTcpServer server = new StubTcpServer(StubTcpServer::silent)) {
            TcpSessionManager manager = new TcpSessionManager(500, 50, 200);
            try {
                manager.reconcile(List.of(cfg("dev-b", server.port()), cfg("dev-a", server.port())));
                awaitTrue(() -> manager.statuses().size() == 2, 2_000);
                assertThat(manager.statuses().stream().map(DeviceSessionStatus::deviceId).toList())
                        .containsExactly("dev-a", "dev-b");
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    void sendToUnknownDeviceThrows() {
        TcpSessionManager manager = new TcpSessionManager(500, 50, 200);
        try {
            assertThatThrownBy(() -> manager.send("ghost", "x".getBytes(StandardCharsets.ISO_8859_1)))
                    .isInstanceOf(SessionSendException.class);
        } finally {
            manager.shutdown();
        }
    }

    private static DeviceSessionConfig cfg(String deviceId, int port) {
        TcpSession session = new TcpSession(TcpSession.Mode.PERSISTENT, TcpSession.Role.CLIENT,
                port, null, null, new TcpSession.Heartbeat(null, null, null, null, 5, 3), null);
        return new DeviceSessionConfig(deviceId, "127.0.0.1", null, session);
    }

    private static void awaitSessionState(TcpSessionManager manager, String deviceId, String state,
                                          long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (manager.statuses().stream()
                    .anyMatch(s -> s.deviceId().equals(deviceId) && s.state().equals(state))) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(manager.statuses()).anySatisfy(s -> {
            assertThat(s.deviceId()).isEqualTo(deviceId);
            assertThat(s.state()).isEqualTo(state);
        });
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
