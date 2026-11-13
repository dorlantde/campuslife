package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.ShopPatchRequest;
import com.campuslife.merchant.dto.BusinessHoursRequest;
import com.campuslife.merchant.service.BusinessHoursService;
import com.campuslife.merchant.service.MerchantOperationService;
import com.campuslife.merchant.vo.MerchantShopView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@Tag(name = "06 商家经营配置", description = "已审核且成员有效的本人商家管理经营资料、开关、营业时间、服务、打印价目、理发师/绑定及按日排班；预约与存量配置保护已接入")
@SecurityRequirement(name = "bearerAuth")
public class MerchantOperationController {
    private final MerchantOperationService operations;
    private final BusinessHoursService hours;
    public MerchantOperationController(MerchantOperationService operations, BusinessHoursService hours) { this.operations = operations; this.hours = hours; }

    @PatchMapping(value = "/api/v1/merchant/shop", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改本人店铺资料与营业开关", description = "要求 ACTIVE / MERCHANT、APPROVED 店铺和 ACTIVE OWNER。"
            + "身份与归属来自会话，不接受 shopId。只修改提供的字段；省略保持原值，null 或错误类型返回 400。"
            + "name、locationText、description 直接生效，不重新审核；分类、校区和审核信息不可修改。"
            + "携带字符串 expectedVersion，实际变更版本加一，无变更不加版本；旧版本仍返回 409。"
            + "营业开关可独立开启/关闭，下单仍须满足营业时段和服务等条件；本接口不操作订单。")
    public ApiResponse<MerchantShopView> patch(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                               @Valid @RequestBody ShopPatchRequest request) {
        return ApiResponse.success(operations.patch(principal, request));
    }

    @PutMapping(value = "/api/v1/merchant/shop/business-hours", consumes = "application/json", produces = "application/json")
    @Operation(summary = "完整替换本人每周营业时间", description = "与经营 PATCH 权限相同，共用 shop.version。严格整数 weekday=1～7（周一至周日），时间为 Asia/Shanghai 的 HH:mm，"
            + "不跨午夜且开始早于结束。原始请求每天最多 4 段、整周最多 28 段；重叠/重复拒绝，相邻自动合并并排序。"
            + "未列出的星期休息，intervals=[] 清空整周；不改变手动营业开关。实际变化版本加一，无变化仍校验版本。"
            + "新营业时间须覆盖今天及之后全部已保存排班（含停用人员），否则 409 SCHEDULE_CONFLICT，先调整/清空排班再修改。成功返回含真实 businessHours 的本人店铺；新营业时间还须覆盖已保护的预约区间，否则409 BOOKING_CONFLICT。")
    public ApiResponse<MerchantShopView> businessHours(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                       @Valid @RequestBody BusinessHoursRequest request) {
        return ApiResponse.success(hours.replace(principal, request));
    }
}
