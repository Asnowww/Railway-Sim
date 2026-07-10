package com.railwaysim.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalServiceTests {

    // ==================== MA 计算 ====================

    @Test
    void singleTrain_maReachesLineEnd() {
        Fixture f = fixture(straightLine(4000));
        TrainState train = train("TR-1", 100);
        f.trackService.updateOccupancy(List.of(train));

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        assertThat(f.signalService.authorities()).hasSize(1);
        MovementAuthority ma = f.signalService.authorities().get(0);
        assertThat(ma.trainId()).isEqualTo("TR-1");
        assertThat(ma.authorityEndMeters()).isEqualTo(4000);
        assertThat(ma.reason()).isEqualTo("前方区段空闲");
    }

    @Test
    void rejectedDepartureRouteHoldsTrainAtZeroSpeedUntilRouteIsEstablished() {
        Fixture f = fixture(straightLine(4000));
        TrainState train = train("TR-1", 100);
        f.trackService.updateOccupancy(List.of(train));
        f.interlockingService.holdTrainUntilRouteEstablished("TR-1", "route conflict");

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        MovementAuthority ma = f.signalService.authorities().get(0);
        assertThat(ma.authorityEndMeters()).isEqualTo(train.positionMeters());
        assertThat(ma.speedLimitMetersPerSecond()).isZero();
        assertThat(ma.reason()).contains("等待进路建立", "route conflict");
    }

    @Test
    void twoTrains_frontMaTruncatedByRearTail() {
        Fixture f = fixture(straightLine(4000));
        SimulationProperties props = new SimulationProperties();
        props.setSafetyGapMeters(120);
        TrackService ts = new TrackService(f.catalog, props);
        ts.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(f.catalog, ts);
        SignalService ss = new SignalService(props, f.catalog, ts, interlocking);

        TrainState front = train("TR-1", 100);
        TrainState rear = train("TR-2", 900);
        // rear 车尾 = 900 - 20 = 880, minus safetyGap(120) = 760
        // So front MA should be 760
        ts.updateOccupancy(List.of(front, rear));

        ss.calculateAuthorities(
            List.of(front, rear),
            ts.constraintsForTrains(List.of(front, rear)),
            List.of()
        );

        assertThat(ss.authorities()).hasSize(2);
        MovementAuthority frontMa = ss.authorities().get(0);
        assertThat(frontMa.trainId()).isEqualTo("TR-1");
        assertThat(frontMa.authorityEndMeters()).isEqualTo(760);
        assertThat(frontMa.reason()).isEqualTo("前车限速");
    }

    @Test
    void faultSegment_truncatesMa() {
        Fixture f = fixture(straightLine(4000));
        SimulationProperties props = new SimulationProperties();
        props.setSafetyGapMeters(120);
        TrackService ts = new TrackService(f.catalog, props);
        ts.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(f.catalog, ts);
        SignalService ss = new SignalService(props, f.catalog, ts, interlocking);

        // 注入故障在 SEG-1 (1000~2000m)，MA 应在 1000-120=880 前截断
        ts.injectFault("SEG-1");
        TrainState train = train("TR-1", 100);
        ts.updateOccupancy(List.of(train));

        ss.calculateAuthorities(
            List.of(train),
            ts.constraintsForTrains(List.of(train)),
            List.of()
        );

        MovementAuthority ma = ss.authorities().get(0);
        assertThat(ma.authorityEndMeters()).isEqualTo(880);
        assertThat(ma.reason()).isEqualTo("故障降级");
    }

    @Test
    void maSpeedCannotExceedSafeBrakingDistance() {
        Fixture f = fixture(straightLine(500));
        // 短线路: 500m, 车在100m, MA=500 → 距离400m
        // safeBrakingSpeed = sqrt(2*0.8*400) = sqrt(640) ≈ 25.3
        // segmentSpeedLimit = 20, so min = 20
        SimulationProperties props = new SimulationProperties();
        props.setSafetyGapMeters(120);
        props.setDefaultSpeedLimitMetersPerSecond(22.2);
        TrackService ts = new TrackService(f.catalog, props);
        ts.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(f.catalog, ts);
        SignalService ss = new SignalService(props, f.catalog, ts, interlocking);

        TrainState train = train("TR-1", 100);
        ts.updateOccupancy(List.of(train));

        ss.calculateAuthorities(
            List.of(train),
            ts.constraintsForTrains(List.of(train)),
            List.of()
        );

        MovementAuthority ma = ss.authorities().get(0);
        assertThat(ma.speedLimitMetersPerSecond()).isLessThanOrEqualTo(22.2);
        assertThat(ma.speedLimitMetersPerSecond()).isGreaterThan(0);
    }

    @Test
    void passedStationDoesNotClampMaToCurrentPosition() {
        Fixture f = fixture(stationLine());
        TrainState rear = train("TR-1", 100);
        TrainState front = train("TR-2", 2600);
        f.trackService.updateOccupancy(List.of(rear, front));

        f.signalService.calculateAuthorities(
            List.of(rear, front),
            f.trackService.constraintsForTrains(List.of(rear, front)),
            List.of()
        );

        MovementAuthority rearMa = authorityFor(f.signalService.authorities(), "TR-1");
        MovementAuthority frontMa = authorityFor(f.signalService.authorities(), "TR-2");
        assertThat(rearMa.authorityEndMeters()).isGreaterThan(rear.positionMeters());
        assertThat(frontMa.authorityEndMeters()).isGreaterThan(front.positionMeters());
        assertThat(rearMa.speedLimitMetersPerSecond()).isGreaterThan(0);
        assertThat(frontMa.speedLimitMetersPerSecond()).isGreaterThan(0);
    }

    @Test
    void completedStationDwellRemainsReleasedUntilTrainDepartsWindow() {
        Fixture f = fixture(stationLine());
        TrainState train = train("TR-1", 1250);
        f.trackService.updateOccupancy(List.of(train));

        for (int i = 0; i < 125; i++) {
            f.signalService.calculateAuthorities(
                List.of(train),
                f.trackService.constraintsForTrains(List.of(train)),
                List.of()
            );
        }

        MovementAuthority releasedMa = authorityFor(f.signalService.authorities(), "TR-1");
        assertThat(releasedMa.authorityEndMeters()).isGreaterThan(train.positionMeters());
        assertThat(releasedMa.speedLimitMetersPerSecond()).isGreaterThan(0);

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        MovementAuthority nextTickMa = authorityFor(f.signalService.authorities(), "TR-1");
        assertThat(nextTickMa.authorityEndMeters()).isGreaterThan(train.positionMeters());
        assertThat(nextTickMa.speedLimitMetersPerSecond()).isGreaterThan(0);
    }

    // ==================== 信号灯色 ====================

    @Test
    void signalAspects_reflectOccupancy() {
        Fixture f = fixture(straightLine(4000));
        TrainState train = train("TR-1", 100);
        f.trackService.updateOccupancy(List.of(train));

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        List<SignalState> signals = f.signalService.signalStates();
        assertThat(signals).isNotEmpty();
        // SEG-0 (0~1000) OCCUPIED → RED
        assertThat(findSignal(signals, "SEG-0").aspect()).isEqualTo(SignalAspect.RED);
        assertThat(findSignal(signals, "SEG-0").reasonTrainId()).isEqualTo("TR-1");
        // SEG-1 (1000~2000) RESERVED → YELLOW
        assertThat(findSignal(signals, "SEG-1").aspect()).isEqualTo(SignalAspect.YELLOW);
        // SEG-2 (2000~3000) RESERVED → YELLOW
        assertThat(findSignal(signals, "SEG-2").aspect()).isEqualTo(SignalAspect.YELLOW);
        // SEG-3 (3000~4000) FREE → GREEN
        assertThat(findSignal(signals, "SEG-3").aspect()).isEqualTo(SignalAspect.GREEN);
    }

    @Test
    void emptyTrains_allSegmentsGreen() {
        Fixture f = fixture(straightLine(4000));

        f.signalService.calculateAuthorities(List.of(), List.of(), List.of());

        // 无列车时区段全部 FREE → GREEN，不返回空列表
        assertThat(f.signalService.signalStates()).isNotEmpty();
        assertThat(f.signalService.signalStates()).allMatch(s -> s.aspect() == SignalAspect.GREEN);
    }

    // ==================== 调度限速 ====================

    @Test
    void dispatchHold_setsZeroSpeed() {
        SimulationProperties props = new SimulationProperties();
        props.setSafetyGapMeters(120);
        props.setDefaultSpeedLimitMetersPerSecond(22.2);
        Fixture f = fixture(straightLine(4000));
        TrackService ts = new TrackService(f.catalog, props);
        ts.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(f.catalog, ts);
        SignalService ss = new SignalService(props, f.catalog, ts, interlocking);

        TrainState train = train("TR-1", 100);
        ts.updateOccupancy(List.of(train));

        ss.calculateAuthorities(
            List.of(train),
            ts.constraintsForTrains(List.of(train)),
            List.of(new DispatchConstraint("TR-1", true, 1.0, 5.0, false, "STOP"))
        );

        // holdTrain=true sets speed → 0 via applyToSpeedLimit
        MovementAuthority ma = ss.authorities().get(0);
        assertThat(ma.speedLimitMetersPerSecond()).isEqualTo(0);
    }

    @Test
    void dispatchSpeedBias_appliesToMaSpeed() {
        SimulationProperties props = new SimulationProperties();
        props.setSafetyGapMeters(120);
        props.setDefaultSpeedLimitMetersPerSecond(22.2);
        Fixture f = fixture(straightLine(4000));
        TrackService ts = new TrackService(f.catalog, props);
        ts.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(f.catalog, ts);
        SignalService ss = new SignalService(props, f.catalog, ts, interlocking);

        TrainState train = train("TR-1", 100);
        ts.updateOccupancy(List.of(train));

        ss.calculateAuthorities(
            List.of(train),
            ts.constraintsForTrains(List.of(train)),
            List.of(new DispatchConstraint("TR-1", false, 0.5, null, false, "HALF_SPEED"))
        );

        // speed factor 0.5 applied to safeBrakingSpeed
        MovementAuthority ma = ss.authorities().get(0);
        double maxSpeedNoDispatch = Math.sqrt(2 * 0.8 * (4000 - 100));
        assertThat(ma.speedLimitMetersPerSecond()).isLessThanOrEqualTo(
            Math.min(22.2, maxSpeedNoDispatch * 0.5)
        );
    }

    // ==================== RESERVED ====================

    @Test
    void reservedOnlyCoversTwoSegments() {
        Fixture f = fixture(fiveSegmentsLine());
        TrainState train = train("TR-1", 100);
        f.trackService.updateOccupancy(List.of(train));

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        // train at S0, MA to end = 5000m, but only 2 segments reserved
        assertThat(occupancy(f, "S0")).isEqualTo(TrackOccupancy.OCCUPIED);
        assertThat(occupancy(f, "S1")).isEqualTo(TrackOccupancy.RESERVED);
        assertThat(occupancy(f, "S2")).isEqualTo(TrackOccupancy.RESERVED);
        assertThat(occupancy(f, "S3")).isEqualTo(TrackOccupancy.FREE);
        assertThat(occupancy(f, "S4")).isEqualTo(TrackOccupancy.FREE);
    }

    // ==================== 故障注入 → 信号同步 ====================

    @Test
    void faultSegment_showsRedSignal() {
        Fixture f = fixture(straightLine(4000));
        f.trackService.injectFault("SEG-1");
        TrainState train = train("TR-1", 100);
        f.trackService.updateOccupancy(List.of(train));

        f.signalService.calculateAuthorities(
            List.of(train),
            f.trackService.constraintsForTrains(List.of(train)),
            List.of()
        );

        assertThat(findSignal(f.signalService.signalStates(), "SEG-1").aspect())
            .isEqualTo(SignalAspect.RED);
        assertThat(findSignal(f.signalService.signalStates(), "SEG-1").reasonTrainId())
            .isEqualTo("FAULT");
    }

    // ==================== 辅助方法 ====================

    private static Fixture fixture(OperationalLineData lineData) {
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData, emptyPowerData());
        SimulationProperties properties = new SimulationProperties();
        properties.setSafetyGapMeters(0);
        TrackService trackService = new TrackService(catalog, properties);
        trackService.reset();
        RouteInterlockingService interlocking = new RouteInterlockingService(catalog, trackService);
        SignalService signalService = new SignalService(properties, catalog, trackService, interlocking);
        return new Fixture(trackService, signalService, interlocking, catalog);
    }

    /** 简单直线: 0→1000→2000→3000→4000, 4段 */
    private static OperationalLineData straightLine(double length) {
        double segLen = length / 4;
        return new OperationalLineData(
            "test-line", "Test",
            List.of(),
            List.of(
                seg("SEG-0", 0, 0, segLen, List.of("SEG-1"), "N0", "N1", "main", 20),
                seg("SEG-1", 1, segLen, segLen * 2, List.of("SEG-2"), "N1", "N2", "main", 22),
                seg("SEG-2", 2, segLen * 2, segLen * 3, List.of("SEG-3"), "N2", "N3", "main", 22),
                seg("SEG-3", 3, segLen * 3, length, List.of(), "N3", "N4", "main", 20)
            ),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    /** 5段直线: 0→1000→2000→3000→4000→5000 */
    private static OperationalLineData fiveSegmentsLine() {
        return new OperationalLineData(
            "test-line", "Test",
            List.of(),
            List.of(
                seg("S0", 0, 0, 1000, List.of("S1"), "N0", "N1", "main", 20),
                seg("S1", 1, 1000, 2000, List.of("S2"), "N1", "N2", "main", 22),
                seg("S2", 2, 2000, 3000, List.of("S3"), "N2", "N3", "main", 22),
                seg("S3", 3, 3000, 4000, List.of("S4"), "N3", "N4", "main", 20),
                seg("S4", 4, 4000, 5000, List.of(), "N4", "N5", "main", 20)
            ),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    /** 5km 直线，站点在 0/1250/2500/3750/5000，用于验证站停窗口不会选中车头后方站点。 */
    private static OperationalLineData stationLine() {
        return new OperationalLineData(
            "station-line", "Station Test",
            List.of(),
            List.of(
                seg("T01", 1, 0, 1250, List.of("T02"), "N1", "N2", "main", 20),
                seg("T02", 2, 1250, 2500, List.of("T03"), "N2", "N3", "main", 22),
                seg("T03", 3, 2500, 3750, List.of("T04"), "N3", "N4", "main", 22),
                seg("T04", 4, 3750, 5000, List.of(), "N4", "N5", "main", 20)
            ),
            List.of(), List.of(), List.of(),
            List.of(
                station("S01", "S01", 0),
                station("S02", "S02", 1250),
                station("S03", "S03", 2500),
                station("S04", "S04", 3750),
                station("S05", "S05", 5000)
            ),
            List.of(), List.of(), List.of(), List.of()
        );
    }

    private static OperationalLineData.TrackSegmentDefinition seg(
        String id, int rawId, double start, double end,
        List<String> forward, String from, String to, String track, double speed
    ) {
        return new OperationalLineData.TrackSegmentDefinition(
            id, rawId, start, end, end - start, speed,
            0, 0, 0, 0, forward, List.of(), from, to, track
        );
    }

    private static OperationalLineData.StationDefinition station(String id, String name, double positionMeters) {
        return new OperationalLineData.StationDefinition(id, name, positionMeters, List.of());
    }

    private static OperationalPowerData emptyPowerData() {
        return new OperationalPowerData(
            1500, 1000, 900, 3000, 3500, 0.02, true, "BRAKE_RESISTOR",
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static TrainState train(String id, double positionMeters) {
        return new TrainEntity(id, "test-line", positionMeters, 20).state();
    }

    private static TrackOccupancy occupancy(Fixture f, String segmentId) {
        return f.trackService.states().stream()
            .filter(s -> s.id().equals(segmentId))
            .map(TrackSegmentState::occupancy)
            .findFirst().orElseThrow();
    }

    private static SignalState findSignal(List<SignalState> signals, String segmentId) {
        return signals.stream()
            .filter(s -> s.segmentId().equals(segmentId))
            .findFirst().orElseThrow();
    }

    private static MovementAuthority authorityFor(List<MovementAuthority> authorities, String trainId) {
        return authorities.stream()
            .filter(authority -> authority.trainId().equals(trainId))
            .findFirst().orElseThrow();
    }

    private record Fixture(
        TrackService trackService,
        SignalService signalService,
        RouteInterlockingService interlockingService,
        StaticInfrastructureCatalog catalog
    ) {
    }
}
