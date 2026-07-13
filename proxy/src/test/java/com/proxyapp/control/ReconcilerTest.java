package com.proxyapp.control;
import com.proxyapp.control.model.ProxyControlState;

import com.proxyapp.profile.DeviceFleetProfile;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.RouteTable;
import com.proxyapp.routing.RoutingState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {

    /**
     * The monotonic guard drops a stale push (older version than what's live) before touching any
     * collaborator — so an out-of-order reconcile can never regress the applied version.
     */
    @Test
    void staleControlStateIsIgnoredAndNeverRegressesAppliedVersion() {
        MessageCatalog catalog = new DeviceFleetProfile().catalog();
        RoutingState routingState = new RoutingState(catalog);
        routingState.update(RouteTable.empty(catalog), true, 5); // pretend v5 is live

        // Only the guard runs for a stale version, so the remaining collaborators stay null.
        Reconciler reconciler = new Reconciler(null, catalog, routingState, null, null, null, null);

        ProxyControlState stale = new ProxyControlState();
        stale.setVersion(3); // older than the live v5
        reconciler.apply(stale);

        assertThat(routingState.appliedVersion()).isEqualTo(5); // unchanged — guard worked, no NPE
    }
}
