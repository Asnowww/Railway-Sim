package com.railwaysim.api;

import com.railwaysim.localnet.LocalNetAdapterManager;
import com.railwaysim.localnet.LocalNetHealth;
import com.railwaysim.localnet.LocalNetReplayResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/localnet")
@CrossOrigin
public class LocalNetController {

    private final LocalNetAdapterManager adapterManager;

    public LocalNetController(LocalNetAdapterManager adapterManager) {
        this.adapterManager = adapterManager;
    }

    @GetMapping("/adapters")
    public List<LocalNetHealth> adapters() {
        return adapterManager.health();
    }

    @GetMapping("/adapters/{adapterId}")
    public LocalNetHealth adapter(@PathVariable String adapterId) {
        return adapterManager.health(adapterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Localnet adapter not found: " + adapterId));
    }

    @PostMapping(
        value = "/adapters/{adapterId}/replay",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public LocalNetReplayResult replay(@PathVariable String adapterId, @RequestBody byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is required");
        }
        return adapterManager.replay(adapterId, payload)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Localnet adapter not found: " + adapterId));
    }
}
