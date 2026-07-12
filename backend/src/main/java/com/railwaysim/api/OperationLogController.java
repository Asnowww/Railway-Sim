package com.railwaysim.api;

import com.railwaysim.api.dto.AuditHealthResponse;
import com.railwaysim.api.dto.OperationLogEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {

    private final ApiOperationLogService operationLogService;

    public OperationLogController(ApiOperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public List<OperationLogEntry> entries(
        @RequestParam(required = false) String targetRef
    ) {
        return targetRef == null || targetRef.isBlank()
            ? operationLogService.entries()
            : operationLogService.entriesForTarget(targetRef);
    }

    @GetMapping("/health")
    public AuditHealthResponse health() {
        long failed = operationLogService.totalFailed();
        long pending = operationLogService.pendingCount();
        String status = failed > 0 ? "DEGRADED" : pending > 0 ? "PENDING" : "UP";
        return new AuditHealthResponse(
            status,
            operationLogService.totalPersisted(),
            failed,
            pending
        );
    }
}
