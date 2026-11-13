package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.ScheduleWrite;
import com.campuslife.merchant.service.BarberScheduleService;
import com.campuslife.merchant.vo.ScheduleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/merchant/barbers/{barberId}/schedule")
@Tag(name = "06 商家经营配置")
@SecurityRequirement(name = "bearerAuth")
public class BarberScheduleController {
    private final BarberScheduleService schedules;
    public BarberScheduleController(BarberScheduleService schedules) { this.schedules = schedules; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "查询本店理发师指定日期排班", description = "正常 MERCHANT + ACTIVE OWNER + APPROVED 的 HAIRCUT 店铺；允许店铺停业、人员停用或无绑定。date 为校园时区严格 YYYY-MM-DD，1000～9999 年的真实日期，可读历史/未来；未配置返回空数组，不创建保护行。barberVersion 为整个人员的当前共享版本。")
    public ApiResponse<ScheduleView> get(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店理发师正整数 ID") @PathVariable String barberId,
            @Parameter(description = "必填 YYYY-MM-DD，Asia/Shanghai", example = "2026-10-22") @RequestParam String date) {
        return ApiResponse.success(schedules.get(principal, barberId, date));
    }

    @PutMapping(consumes = "application/json", produces = "application/json")
    @Operation(summary = "完整替换理发师指定日期排班", description = "Asia/Shanghai 今天至第 6 天；原始 0～4 段 HH:mm、15 分钟对齐，排序合并相邻，重叠拒绝，每段须位于一个连续营业区间。空数组清空该日。expectedBarberVersion 与姓名/启停、绑定及其他日期共用；实际变化仅增加人员版本一次，无变化仍校验旧版本和日期。清空保留日期保护行；失败整体回滚。排班不等于可预约，本接口不创建订单。")
    public ApiResponse<ScheduleView> put(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店理发师正整数 ID") @PathVariable String barberId, @Valid @RequestBody ScheduleWrite request) {
        return ApiResponse.success(schedules.replace(principal, barberId, request));
    }
}
