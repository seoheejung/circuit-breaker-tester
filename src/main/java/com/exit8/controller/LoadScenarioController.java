package com.exit8.controller;

import com.exit8.dto.DefaultResponse;
import com.exit8.service.LoadScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/load")
@RequiredArgsConstructor
public class LoadScenarioController {

    private final LoadScenarioService loadScenarioService;

    @PostMapping("/cpu")
    public DefaultResponse<Void> cpuLoad(@RequestParam long durationMs) {
        loadScenarioService.generateCpuLoad(durationMs);
        return DefaultResponse.success(HttpStatus.OK.value());
    }

    @PostMapping("/db-read")
    public DefaultResponse<Void> dbReadLoad(
            @RequestParam(name = "repeatCount", defaultValue = "1") int repeatCount) {
        loadScenarioService.simulateDbReadLoad(repeatCount);
        return DefaultResponse.success(HttpStatus.OK.value());
    }

    @PostMapping("/db-write")
    public DefaultResponse<Void> dbWrite(
        @RequestParam(name = "repeatCount", defaultValue = "1") int repeatCount) {
        loadScenarioService.simulateDbWriteLoad(repeatCount);
        return DefaultResponse.success(HttpStatus.OK.value());
    }

    // Warm-up 실행
    @PostMapping("/redis/warmup")
    public DefaultResponse<Void> warmup() {
        loadScenarioService.simulateDbReadLoad(500);
        return DefaultResponse.success(HttpStatus.OK.value());
    }
}