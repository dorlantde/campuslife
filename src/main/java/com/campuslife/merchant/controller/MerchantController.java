package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.MerchantApplicationRequest;
import com.campuslife.merchant.dto.MerchantResubmitRequest;
import com.campuslife.merchant.service.MerchantApplicationService;
import com.campuslife.merchant.service.MerchantResubmissionService;
import com.campuslife.merchant.service.MerchantShopService;
import com.campuslife.merchant.vo.MerchantApplicationView;
import com.campuslife.merchant.vo.MerchantShopView;
import com.campuslife.merchant.vo.ShopCategoryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@Tag(name = "03 商家入驻", description = "查询分类、提交申请、查看本人审核结果；被拒后可修改资料重新提交")
public class MerchantController {
    private final MerchantApplicationService applications;
    private final MerchantShopService shops;
    private final MerchantResubmissionService resubmissions;
    public MerchantController(MerchantApplicationService applications, MerchantShopService shops, MerchantResubmissionService resubmissions) {
        this.applications = applications; this.shops = shops; this.resubmissions = resubmissions;
    }

    @GetMapping(value = "/api/v1/shop-categories", produces = "application/json")
    @Operation(summary = "查询启用的商家分类", description = "匿名可用；按 sortOrder、id 升序。复制返回的字符串 id 用于申请。")
    public ApiResponse<List<ShopCategoryView>> categories() { return ApiResponse.success(shops.categories()); }

    @PostMapping(value = "/api/v1/merchant-applications", consumes = "application/json", produces = "application/json")
    @Operation(summary = "提交商家入驻申请", description = "独立创建 ACTIVE / MERCHANT、PENDING 店铺和 PENDING OWNER，营业开关为 false；不自动登录。"
            + "同一事务提交，失败全部回滚。201 成功；400 参数或分类无效；409 USERNAME_EXISTS，登录名在所有角色间唯一。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "创建待审核商家成功", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<MerchantApplicationView>> apply(@Valid @RequestBody MerchantApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(applications.apply(request)));
    }

    @GetMapping(value = "/api/v1/merchant/shop", produces = "application/json")
    @Operation(summary = "读取本人店铺及审核状态", description = "MERCHANT 登录后可用，PENDING / REJECTED 也可查看。店铺归属从会话读取，无需 shopId。"
            + "其他角色 403 ROLE_FORBIDDEN；无归属店铺 404；账号停用或会话失效按认证规则拒绝。")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<MerchantShopView> own(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(shops.own(principal));
    }

    @PostMapping(value = "/api/v1/merchant/shop/resubmit", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改并重新提交被拒申请", description = "仅本人 MERCHANT 的 REJECTED 店铺可用，归属由会话确定。完整提交店铺资料与字符串 expectedVersion；"
            + "复用原账号和店铺，版本加一并进入 PENDING，成员保持 PENDING、默认停业。清除当前审核信息，保留历史。"
            + "PENDING / APPROVED / SUSPENDED 不能重提；旧版本或重复请求返回 409。")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<MerchantShopView> resubmit(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                @Valid @RequestBody MerchantResubmitRequest request) {
        return ApiResponse.success(resubmissions.resubmit(principal, request));
    }
}
