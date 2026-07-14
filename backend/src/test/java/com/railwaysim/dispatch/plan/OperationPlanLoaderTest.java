package com.railwaysim.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.railwaysim.dispatch.config.DispatchProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class OperationPlanLoaderTest {

    private OperationPlanLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        DispatchProperties properties = new DispatchProperties();
        properties.setPlanLocation("classpath:config/dispatch-plan.yaml");
        loader = new OperationPlanLoader(properties, new DefaultResourceLoader());
        loader.load();
    }

    @Test
    void resolvesFlatPeriodAtNoon() {
        Instant noon = LocalDate.now().atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant();
        CurrentRunPlan plan = loader.resolve(noon);
        assertEquals("FLAT", plan.periodType());
        assertEquals(300, plan.departureIntervalSec());
    }

    @Test
    void resolvesPeakPeriodAtEight() {
        Instant eight = LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant();
        CurrentRunPlan plan = loader.resolve(eight);
        assertEquals("PEAK", plan.periodType());
        assertEquals(180, plan.departureIntervalSec());
    }

    @Test
    void loadsFormalServicesAndCirculations() {
        assertEquals(4, loader.services().size());
        assertEquals(4, loader.circulations().size());
        TrainServicePlan first = loader.services().getFirst();
        assertEquals("SVC-001", first.serviceId());
        assertEquals("CIRC-001", first.circulationId());
        assertEquals("TR-001", first.trainId());
        assertEquals("S101", first.origin().stationId());
        assertEquals("S113", first.terminus().stationId());
        assertFalse(first.stops().isEmpty());

        TrainServicePlan firstDown = loader.services().stream()
            .filter(service -> "SVC-003".equals(service.serviceId()))
            .findFirst()
            .orElseThrow();
        assertEquals("SVC-003", firstDown.serviceId());
        assertEquals("DOWN", firstDown.direction());
        assertEquals("S113", firstDown.origin().stationId());
        assertEquals("S101", firstDown.terminus().stationId());
    }

    @Test
    void offsetsSecondTrainInEachDirectionByCurrentPeriodInterval() {
        CurrentRunPlan peak = loader.resolve(
            LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant());
        CurrentRunPlan flat = loader.resolve(
            LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
        CurrentRunPlan offPeak = loader.resolve(
            LocalDate.now().atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant());
        TrainServicePlan upFollower = service("SVC-002");
        TrainServicePlan downFollower = service("SVC-004");

        assertEquals(195, loader.plannedDepartureOffsetSec(upFollower, peak));
        assertEquals(195, loader.plannedDepartureOffsetSec(downFollower, peak));
        assertEquals(315, loader.plannedDepartureOffsetSec(upFollower, flat));
        assertEquals(315, loader.plannedDepartureOffsetSec(downFollower, flat));
        assertEquals(435, loader.plannedDepartureOffsetSec(upFollower, offPeak));
        assertEquals(435, loader.plannedDepartureOffsetSec(downFollower, offPeak));
    }

    private TrainServicePlan service(String serviceId) {
        return loader.services().stream()
            .filter(service -> serviceId.equals(service.serviceId()))
            .findFirst()
            .orElseThrow();
    }
}
