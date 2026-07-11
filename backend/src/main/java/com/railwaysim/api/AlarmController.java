package com.railwaysim.api;

import com.railwaysim.monitor.AlarmLifecycleService;
import com.railwaysim.monitor.AlarmRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {
    private final AlarmLifecycleService alarmService;

    public AlarmController(AlarmLifecycleService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping
    public List<AlarmRecord> list(@RequestParam(required = false) String runId) {
        return alarmService.records(runId);
    }

    @PostMapping("/{alarmId}/acknowledge")
    public AlarmRecord acknowledge(@PathVariable String alarmId,
        @RequestBody(required = false) AlarmAcknowledgeRequest request) {
        try {
            return alarmService.acknowledge(
                alarmId, request == null ? null : request.operator(), Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public record AlarmAcknowledgeRequest(String operator) {}
}
