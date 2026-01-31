package com.exit8.controller;

import com.exit8.dto.DefaultResponse;
import com.exit8.service.SystemHealthService;
import com.exit8.dto.SystemHealthStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    @GetMapping("/health")
    public DefaultResponse<SystemHealthStatus> health() {
        return DefaultResponse.success(
                systemHealthService.getCurrentStatus()
        );
    }
}
