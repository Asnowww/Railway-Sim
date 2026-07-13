package com.railwaysim.signal.dispatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SignalDispatchPlanRegistry {

    private final Map<String, SignalDispatchPlanPublication> publications = new LinkedHashMap<>();
    private String latestPublicationId;

    public synchronized SignalDispatchPlanPublication accept(SignalDispatchPlanPublication publication) {
        if (publication == null) {
            throw new IllegalArgumentException("publication is required");
        }
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
