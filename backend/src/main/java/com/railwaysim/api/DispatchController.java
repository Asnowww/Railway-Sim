package com.railwaysim.api;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch")
@CrossOrigin
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/commands")
    public List<DispatchCommand> commands() {
        return dispatchService.pendingCommands();
    }

    @PostMapping("/commands")
    public DispatchCommand submit(@Valid @RequestBody DispatchCommandRequest request) {
        DispatchCommand command = new DispatchCommand(
            "DC-" + UUID.randomUUID(),
            request.trainId(),
            request.commandType(),
            request.detail(),
            Instant.now()
        );
        dispatchService.submit(command);
        return command;
    }

    public record DispatchCommandRequest(
        @NotBlank String trainId,
        @NotBlank String commandType,
        String detail
    ) {
    }
}
