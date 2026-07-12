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
        assertEquals("S01", first.origin().stationId());
        assertEquals("S05", first.terminus().stationId());
        assertFalse(first.stops().isEmpty());
    }
}
