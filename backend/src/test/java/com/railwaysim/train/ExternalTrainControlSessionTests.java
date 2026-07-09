package com.railwaysim.train;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import org.junit.jupiter.api.Test;

class ExternalTrainControlSessionTests {

    @Test
    void attachesExternalControlSessionThroughSignalAndPowerNetworks() {
        ExternalTrainControlSession session = ExternalTrainControlSession.connecting(
            "TR-003",
            15,
            320,
            ExternalTrainDirection.DOWN
        );

        session.advance(null, null);
        assertThat(session.state()).isEqualTo(ExternalTrainControlSessionState.SIGNAL_ATTACHING);
        assertThat(session.signalNetworkStatus()).isEqualTo("ATTACHING");

        session.advance(new MovementAuthority("TR-003", 1_000, 13.33, "route ready", "S1"), null);
        assertThat(session.state()).isEqualTo(ExternalTrainControlSessionState.POWER_ATTACHING);
        assertThat(session.signalNetworkStatus()).isEqualTo("ATTACHED");

        session.advance(null, new PowerConstraint("TR-003", "P01", 1500, 3_200_000, true));
        session.advance(null, null);

        assertThat(session.state()).isEqualTo(ExternalTrainControlSessionState.IN_SERVICE);
        assertThat(session.powerNetworkStatus()).isEqualTo("ATTACHED");
        assertThat(session.reason()).isEqualTo("EXTERNAL_CONTROL_IN_SERVICE");
    }

    @Test
    void detachesExternalControlSessionBeforeRemoval() {
        ExternalTrainControlSession session = ExternalTrainControlSession.inService(
            "TR-003",
            15,
            320,
            ExternalTrainDirection.DOWN
        );

        session.requestDetach("SIGNAL_DELETE_TRAIN");
        assertThat(session.state()).isEqualTo(ExternalTrainControlSessionState.SIGNAL_DETACHING);
        assertThat(session.signalNetworkStatus()).isEqualTo("DETACHING");

        session.advance(null, null);
        session.advance(null, null);

        assertThat(session.disconnected()).isTrue();
        assertThat(session.signalNetworkStatus()).isEqualTo("DETACHED");
        assertThat(session.powerNetworkStatus()).isEqualTo("DETACHED");
    }
}
