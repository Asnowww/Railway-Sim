package com.railwaysim.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
