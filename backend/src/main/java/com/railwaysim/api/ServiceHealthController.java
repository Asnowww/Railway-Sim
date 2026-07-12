package com.railwaysim.api;

import com.railwaysim.monitor.ServiceHealthRecord;
import com.railwaysim.monitor.ServiceHealthService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/service-health")
public class ServiceHealthController {
    private final ServiceHealthService service;

    public ServiceHealthController(ServiceHealthService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServiceHealthRecord> list() {
        return service.records();
    }

    @GetMapping("/{serviceId}")
    public ServiceHealthRecord detail(@PathVariable String serviceId) {
        try {
            return service.require(serviceId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{serviceId}/recovery/check")
    public ServiceHealthRecord checkRecovery(
        @PathVariable String serviceId, @RequestBody RecoveryCheckRequest request
    ) {
        try {
            return service.checkRecovery(
                serviceId, request.expectedRunId(), request.expectedTick(), Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public record RecoveryCheckRequest(String expectedRunId, long expectedTick) {
    }
}
