package com.railwaysim.signal.dispatch;

import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.RouteStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SignalDispatchPlanRegistry {

    private static final Logger log = LoggerFactory.getLogger(SignalDispatchPlanRegistry.class);

    private final Map<String, SignalDispatchPlanPublication> publications = new LinkedHashMap<>();
    private String latestPublicationId;
    private final RouteInterlockingService interlockingService;

    public SignalDispatchPlanRegistry(RouteInterlockingService interlockingService) {
        this.interlockingService = interlockingService;
    }

    /** 接收并逐条校验发布计划。逐条检查routeId存在且可用。 */
    public synchronized SignalDispatchPlanPublication acceptAndValidate(
        SignalDispatchPlanPublication publication
    ) {
        if (publication == null) throw new IllegalArgumentException("publication is required");
        List<SignalDispatchPlanPublication.Entry> validated = new ArrayList<>();
        int accepted = 0, rejected = 0;
        for (SignalDispatchPlanPublication.Entry entry : publication.entries()) {
            String rejection = null;
            if (entry.routeId() != null && !entry.routeId().isBlank()) {
                var state = interlockingService.state(entry.routeId());
                if (state == null) {
                    rejection = "ROUTE_NOT_FOUND";
                } else if (state.status().holdsInterlockingResources()
                    && !entry.trainId().equals(state.establishedByTrainId())) {
                    rejection = "ROUTE_CONFLICT:" + state.status().name();
                } else if (state.status() == RouteStatus.FAILED) {
                    rejection = "ROUTE_FAILED";
                }
            }
            validated.add(new SignalDispatchPlanPublication.Entry(
                entry.entryId(), entry.sourceType(), entry.sourceId(),
                entry.trainId(), entry.routeId(), entry.routeName(),
                entry.direction(), entry.originPointId(), entry.destinationPointId(),
                entry.viaPointIds(), entry.stationIds(), entry.segmentIds(),
                entry.plannedDepartureAt(),
                rejection == null ? "ACCEPTED" : "REJECTED", rejection));
            if (rejection == null) accepted++; else rejected++;
        }
        var acked = new SignalDispatchPlanPublication(
            publication.publicationId(), publication.simulationRunId(),
            publication.dispatchPlanId(), publication.lineId(),
            publication.effectiveFrom(), Instant.now(), publication.operator(),
            rejected == 0 ? "ALL_ACCEPTED" : (accepted == 0 ? "ALL_REJECTED" : "PARTIAL"),
            accepted, rejected, validated);
        publications.put(acked.publicationId(), acked);
        latestPublicationId = acked.publicationId();
        log.info("[DispatchPlanRegistry] publication {} validated: {} accepted / {} rejected",
            acked.publicationId(), accepted, rejected);
        return acked;
    }

    public synchronized SignalDispatchPlanPublication accept(SignalDispatchPlanPublication publication) {
        if (publication == null) throw new IllegalArgumentException("publication is required");
        publications.put(publication.publicationId(), publication);
        latestPublicationId = publication.publicationId();
        return publication;
    }

    public synchronized SignalDispatchPlanPublication latest() {
        return latestPublicationId == null ? null : publications.get(latestPublicationId);
    }

    public synchronized List<SignalDispatchPlanPublication> list() {
        return new ArrayList<>(publications.values()).stream()
            .sorted(Comparator.comparing(SignalDispatchPlanPublication::publishedAt).reversed())
            .toList();
    }

    public synchronized void clear() {
        publications.clear();
        latestPublicationId = null;
    }
}
