package com.railwaysim.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.railwaysim.api.SimulationWebSocketHandler;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.dispatch.integration.DispatchCommandPublisher;
import com.railwaysim.monitor.MonitorService;
import com.railwaysim.power.PowerService;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalService;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationRuntimeTests {

    @Test
    void scheduledTickRecordsFailureAndStopsRuntime() throws Exception {
        TrainManager trainManager = mock(TrainManager.class);
        TrackService trackService = mock(TrackService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        SimulationRunService runService = mock(SimulationRunService.class);
        SimulationRunContext runContext = mock(SimulationRunContext.class);
        when(dispatchService.simulationRunId()).thenReturn("run-scheduled");
        when(trainManager.states()).thenReturn(List.of());
        doThrow(new IllegalStateException("scheduled failure"))
            .when(trackService).updateOccupancy(any());

        SimulationRuntime runtime = new SimulationRuntime(
            trainManager, trackService, mock(SignalService.class), mock(PowerService.class), dispatchService,
            mock(DispatchCommandPublisher.class), mock(MonitorService.class), mock(SimulationWebSocketHandler.class),
            new SimulationProperties(), mock(SimpleEventBus.class), mock(RealtimeStateCache.class),
            mock(SimulationPersistenceService.class), mock(RouteInterlockingService.class),
            mock(VehicleRuntimeIntegrationService.class), runService, runContext,
            mock(TrainStopEvaluationService.class), mock(FinalControlDecisionPersistenceService.class),
            mock(com.railwaysim.infrastructure.StaticInfrastructureCatalog.class)
        );
        Field status = SimulationRuntime.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(runtime, SimulationStatus.RUNNING);

        assertThatThrownBy(runtime::advanceScheduledTick)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("scheduled failure");

        assertThat(status.get(runtime)).isEqualTo(SimulationStatus.STOPPED);
        verify(runService).fail(eq("run-scheduled"), eq(1L), any(), eq("IllegalStateException:scheduled failure"));
    }
}
