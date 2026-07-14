package com.railwaysim.api;

import com.railwaysim.vehicle.external.ExternalSimManualBridge;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/external-simulator/manual-bridge")
@CrossOrigin
public class ExternalSimManualBridgeController {

    private final ExternalSimManualBridge bridge;

    public ExternalSimManualBridgeController(ExternalSimManualBridge bridge) {
        this.bridge = bridge;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return bridge.status();
    }

    /**
     * 前端手动/ATO 切换入口。body: {"mode": "MANUAL"|"ATO"|"AUTO"}。
     * AUTO = 跟随司机台输入的 atoModeActive 位（前端 PLC 输入即可切换，无需调本接口）。
     */
    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, String> request) {
        String raw = request == null ? null : request.get("mode");
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required: MANUAL | ATO | AUTO");
        }
        final ExternalSimManualBridge.BridgeMode mode;
        try {
            mode = ExternalSimManualBridge.BridgeMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported mode: " + raw);
        }
        bridge.setModeOverride(mode);
        return bridge.status();
    }
}
