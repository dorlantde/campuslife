package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.ServiceCreateRequest;
import com.campuslife.merchant.dto.ServicePatchRequest;
import com.campuslife.merchant.service.ServiceCatalogService;
import com.campuslife.merchant.vo.MerchantServiceView;
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
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/merchant/services")
@Tag(name = "06 商家经营配置")
@SecurityRequirement(name = "bearerAuth")
public class MerchantServiceController {
    private final ServiceCatalogService services;
    public MerchantServiceController(ServiceCatalogService services) { this.services = services; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "查询本店全部服务", description = "要求正常 MERCHANT、APPROVED 店铺及 ACTIVE OWNER。含上架/下架；可筛选 serviceType、enabled，按 created_at DESC、id DESC。返回数组，不分页。")
    public ApiResponse<List<MerchantServiceView>> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "HAIRCUT / PRINT，可省略") @RequestParam(required = false) String serviceType,
            @Parameter(description = "true / false，可省略") @RequestParam(required = false) String enabled) {
        return ApiResponse.success(services.list(principal, serviceType, enabled));
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(summary = "新增本店服务", description = "服务类型与店铺分类匹配，理发金额为固定价、时长为 15～240 的 15 分钟倍数；打印时长必须显式 null、金额仅参考起价。"
            + "金额为严格整数分 1～100000。省略 enabled 默认下架，description 默认空字符串；不接受可选字段 null。版本从 0 开始；重复提交会创建新 ID，不自动去重。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "服务创建成功", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<MerchantServiceView>> create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                                  @Valid @RequestBody ServiceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(services.create(principal, request)));
    }

    @PatchMapping(value = "/{serviceId}", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改本店服务与上下架", description = "expectedVersion 取服务查询，不是店铺版本。未传字段保留，介绍用空字符串清空；仅 PRINT 时长允许 null。"
            + "实际变更才加服务版本，无变更仍校验版本和权限；不更改 shop.version。类型/归属不可修改，其他店铺服务统一 404。上架不保证可接单，下架不删除或操作存量订单。")
    public ApiResponse<MerchantServiceView> patch(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店服务正十进制 ID") @PathVariable String serviceId,
            @Valid @RequestBody ServicePatchRequest request) {
        return ApiResponse.success(services.patch(principal, serviceId, request));
    }
}
