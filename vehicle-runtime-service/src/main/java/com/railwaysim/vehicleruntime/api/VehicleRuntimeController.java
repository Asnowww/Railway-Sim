package com.railwaysim.vehicleruntime.api;

import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeBootstrapRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeEvent;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeHealth;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeInstanceState;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchResponse;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import com.railwaysim.vehicleruntime.runtime.VehicleRuntimeManager;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部车辆运行时 HTTP 边界，前端不直接访问，中央只通过受控接口同步状态。
 */
@RestController
@RequestMapping("/vehicle-runtime")
public class VehicleRuntimeController {

    private final VehicleRuntimeManager manager;

    public VehicleRuntimeController(VehicleRuntimeManager manager) {
        this.manager = manager;
    }

    @GetMapping("/health")
    public VehicleRuntimeHealth health() {
        return manager.health();
    }

    @PostMapping("/bootstrap")
    public VehicleRuntimeHealth bootstrap(@RequestBody(required = false) VehicleRuntimeBootstrapRequest request) {
        return manager.bootstrap(request);
    }

    @PutMapping("/trains/{trainId}")
    public VehicleRuntimeInstanceState register(@PathVariable String trainId, @RequestBody(required = false) TrainStateSnapshot train) {
        // 兼容旧中央反向注册；请求体为空时用路径生成最小实例，避免旧联调调用失败。
        if (train != null && train.id() != null && !train.id().isBlank() && !trainId.equals(train.id())) {
            // path 是路由权威标识，body 不一致直接拒绝，避免注册出另一个列车实例。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path trainId and body id must match");
        }
        TrainStateSnapshot effective = train == null || train.id() == null || train.id().isBlank() ? minimalTrain(trainId) : train;
        return manager.register(effective);
    }

    @PostMapping("/trains/launch")
    public VehicleRuntimeLaunchResponse launch(@RequestBody VehicleRuntimeLaunchRequest request) {
        // 新启动流程由车辆仿真服务主动拉起实例，再向中央系统注册镜像。
        return manager.launch(request);
    }

    @DeleteMapping("/trains/{trainId}")
    public void remove(@PathVariable String trainId) {
        manager.remove(trainId);
    }

    @DeleteMapping("/trains")
    public void clear() {
        manager.clear();
    }

    @GetMapping("/instances")
    public List<VehicleRuntimeInstanceState> instances() {
        return manager.instances();
    }

    @PostMapping("/step-fleet")
    public VehicleRuntimeStepResponse stepFleet(@RequestBody VehicleRuntimeStepRequest request) {
        return manager.stepFleet(request);
    }

    @GetMapping("/events")
    public List<VehicleRuntimeEvent> events() {
        return manager.events();
    }

    private TrainStateSnapshot minimalTrain(String trainId) {
        return new TrainStateSnapshot(
            trainId,
            "",
            trainId,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "EXTERNAL_RUNTIME_REGISTERED",
            0,
            "UNKNOWN",
            0,
            0,
            120,
            0,
            0,
            0,
            0,
            "NORMAL",
            4,
            4,
            "NONE",
            "RUNNING",
            "ATO",
            true,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "COASTING",
            "INITIAL",
            22.2,
            0,
            0,
            1_000_000,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK"
        );
    }
}
