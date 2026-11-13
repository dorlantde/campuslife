package com.campuslife.admin.controller;

import com.campuslife.admin.dto.ShopApprovalRequest;
import com.campuslife.admin.dto.ShopRejectionRequest;
import com.campuslife.admin.service.ShopReviewService;
import com.campuslife.admin.vo.AdminShopPage;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.vo.MerchantShopView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/shops")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "08 管理员", description = "使用受控创建的 ADMIN 业务账号登录；所有接口要求管理员身份")
public class AdminShopController {
    private final ShopReviewService reviews;
    public AdminShopController(ShopReviewService reviews) { this.reviews = reviews; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "分页查询商家申请", description = "approvalStatus 可不传，或 PENDING / APPROVED / REJECTED / SUSPENDED；page 默认 1，size 默认 20、最大 100；createdAt、id 倒序。")
    public ApiResponse<AdminShopPage> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String approvalStatus, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) { return ApiResponse.success(reviews.list(principal,approvalStatus,page,size)); }

    @GetMapping(value = "/{shopId}", produces = "application/json")
    @Operation(summary = "读取商家申请详情", description = "读取 version 字符串用于审核的 expectedVersion；店铺不存在返回 404。")
    public ApiResponse<MerchantShopView> detail(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String shopId) { return ApiResponse.success(reviews.detail(principal,shopId)); }

    @PostMapping(value = "/{shopId}/approve", consumes = "application/json", produces = "application/json")
    @Operation(summary = "批准商家申请", description = "仅 PENDING 且版本匹配；同事务激活 OWNER、追加审核记录，仍保持停业。旧版本或版本上限 409 CONFIG_VERSION_CONFLICT；非待审 409 SHOP_STATE_CONFLICT。")
    public ApiResponse<MerchantShopView> approve(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String shopId, @RequestBody ShopApprovalRequest request) {
        return ApiResponse.success(reviews.decide(principal,shopId,request.expectedVersion(),true,null));
    }

    @PostMapping(value = "/{shopId}/reject", consumes = "application/json", produces = "application/json")
    @Operation(summary = "拒绝商家申请", description = "仅 PENDING 且版本匹配；原因去首尾空白后 1～300 Unicode 码点。店主保持 PENDING，保存审核记录，版本 + 1；冲突规则与批准相同。")
    public ApiResponse<MerchantShopView> reject(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String shopId, @RequestBody ShopRejectionRequest request) {
        return ApiResponse.success(reviews.decide(principal,shopId,request.expectedVersion(),false,request.reason()));
    }
}
