package com.railwaysim.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteStateTests {

    @Test
    void everyDeclaredStatusHasAValidEntryTransition() {
        RouteState initial = state(RouteStatus.AVAILABLE);
        RouteState validating = initial.transitionTo(RouteStatus.VALIDATING);
        RouteState setting = validating.transitionTo(RouteStatus.SETTING_SWITCHES);
        RouteState locked = setting.withLocked(Set.of("SW-1"), "TR-1");
        RouteState occupied = locked.transitionTo(RouteStatus.OCCUPIED);
        RouteState releasing = occupied.transitionTo(RouteStatus.RELEASING);

        Map<RouteStatus, RouteState> entered = new EnumMap<>(RouteStatus.class);
        entered.put(RouteStatus.AVAILABLE,
            initial.transitionTo(RouteStatus.EXPIRED_BY_RESET).transitionTo(RouteStatus.AVAILABLE));
        entered.put(RouteStatus.VALIDATING, validating);
        entered.put(RouteStatus.SETTING_SWITCHES, setting);
        entered.put(RouteStatus.LOCKED, locked);
        entered.put(RouteStatus.OCCUPIED, occupied);
        entered.put(RouteStatus.RELEASING, releasing);
        entered.put(RouteStatus.RELEASED, releasing.transitionTo(RouteStatus.RELEASED));
        entered.put(RouteStatus.CONFLICTED, validating.transitionTo(RouteStatus.CONFLICTED));
        entered.put(RouteStatus.REJECTED, validating.transitionTo(RouteStatus.REJECTED));
        entered.put(RouteStatus.FAILED, setting.transitionTo(RouteStatus.FAILED));
        entered.put(RouteStatus.CANCELLED, validating.transitionTo(RouteStatus.CANCELLED));
        entered.put(RouteStatus.EXPIRED_BY_RESET, initial.transitionTo(RouteStatus.EXPIRED_BY_RESET));

        assertThat(entered.keySet()).containsExactlyInAnyOrder(RouteStatus.values());
        entered.forEach((status, route) -> assertThat(route.status()).isEqualTo(status));
    }

    @Test
    void everyDeclaredStatusHasAValidExitTransition() {
        Map<RouteStatus, RouteState> sources = sourceStates();

        Map<RouteStatus, RouteStatus> exits = Map.ofEntries(
            Map.entry(RouteStatus.AVAILABLE, RouteStatus.VALIDATING),
            Map.entry(RouteStatus.VALIDATING, RouteStatus.REJECTED),
            Map.entry(RouteStatus.SETTING_SWITCHES, RouteStatus.FAILED),
            Map.entry(RouteStatus.LOCKED, RouteStatus.OCCUPIED),
            Map.entry(RouteStatus.OCCUPIED, RouteStatus.RELEASING),
            Map.entry(RouteStatus.RELEASING, RouteStatus.RELEASED),
            Map.entry(RouteStatus.RELEASED, RouteStatus.AVAILABLE),
            Map.entry(RouteStatus.CONFLICTED, RouteStatus.AVAILABLE),
            Map.entry(RouteStatus.REJECTED, RouteStatus.AVAILABLE),
            Map.entry(RouteStatus.FAILED, RouteStatus.AVAILABLE),
            Map.entry(RouteStatus.CANCELLED, RouteStatus.AVAILABLE),
            Map.entry(RouteStatus.EXPIRED_BY_RESET, RouteStatus.AVAILABLE)
        );

        exits.forEach((source, target) ->
            assertThat(sources.get(source).transitionTo(target).status()).isEqualTo(target));
        assertThat(exits.keySet()).containsExactlyInAnyOrder(RouteStatus.values());
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThatThrownBy(() -> state(RouteStatus.AVAILABLE).transitionTo(RouteStatus.OCCUPIED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AVAILABLE -> OCCUPIED");
        assertThatThrownBy(() -> sourceStates().get(RouteStatus.LOCKED).transitionTo(RouteStatus.AVAILABLE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCKED -> AVAILABLE");
        assertThatThrownBy(() -> sourceStates().get(RouteStatus.SETTING_SWITCHES)
                .withLocked(Set.of(), " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trainId is required");
    }

    private static Map<RouteStatus, RouteState> sourceStates() {
        RouteState available = state(RouteStatus.AVAILABLE);
        RouteState validating = available.transitionTo(RouteStatus.VALIDATING);
        RouteState setting = validating.transitionTo(RouteStatus.SETTING_SWITCHES);
        RouteState locked = setting.withLocked(Set.of("SW-1"), "TR-1");
        RouteState occupied = locked.transitionTo(RouteStatus.OCCUPIED);
        RouteState releasing = occupied.transitionTo(RouteStatus.RELEASING);

        Map<RouteStatus, RouteState> states = new EnumMap<>(RouteStatus.class);
        states.put(RouteStatus.AVAILABLE, available);
        states.put(RouteStatus.VALIDATING, validating);
        states.put(RouteStatus.SETTING_SWITCHES, setting);
        states.put(RouteStatus.LOCKED, locked);
        states.put(RouteStatus.OCCUPIED, occupied);
        states.put(RouteStatus.RELEASING, releasing);
        states.put(RouteStatus.RELEASED, releasing.transitionTo(RouteStatus.RELEASED));
        states.put(RouteStatus.CONFLICTED, validating.transitionTo(RouteStatus.CONFLICTED));
        states.put(RouteStatus.REJECTED, validating.transitionTo(RouteStatus.REJECTED));
        states.put(RouteStatus.FAILED, setting.transitionTo(RouteStatus.FAILED));
        states.put(RouteStatus.CANCELLED, validating.transitionTo(RouteStatus.CANCELLED));
        states.put(RouteStatus.EXPIRED_BY_RESET, available.transitionTo(RouteStatus.EXPIRED_BY_RESET));
        return states;
    }

    private static RouteState state(RouteStatus status) {
        return new RouteState("R-1", status, Set.of(), null, Set.of("SEG-1"));
    }
}
