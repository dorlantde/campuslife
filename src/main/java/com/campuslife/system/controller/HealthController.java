package com.campuslife.system.controller;

import com.campuslife.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "00 系统", description = "匿名存活检查")
public class HealthController {
    private final Clock clock;

    public HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    @Operation(summary = "检查应用是否运行", description = "匿名接口，返回 200 / OK / UP。仅表示 HTTP 存活。")
    public ApiResponse<HealthView> health() {
        return ApiResponse.success(new HealthView("CampusLife", "UP", clock.instant()));
    }

    public record HealthView(String application, String status, Instant timestamp) {
    }
}
