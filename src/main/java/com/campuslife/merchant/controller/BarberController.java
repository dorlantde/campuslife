package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.BarberCreateRequest;
import com.campuslife.merchant.dto.BarberPatchRequest;
import com.campuslife.merchant.dto.BarberServicesRequest;
import com.campuslife.merchant.service.BarberManagementService;
import com.campuslife.merchant.vo.MerchantBarberView;
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
@RequestMapping("/api/v1/merchant/barbers")
@Tag(name = "06 商家经营配置")
@SecurityRequirement(name = "bearerAuth")
public class BarberController {
    private final BarberManagementService barbers;
    public BarberController(BarberManagementService barbers) { this.barbers = barbers; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "查询本店理发师", description = "要求正常 MERCHANT、APPROVED 的 HAIRCUT 店铺及 ACTIVE OWNER。返回含停用人员的数组，按创建时间/ID 倒序；serviceIds 数值升序，含下架服务，不分页。理发师不是登录账号。")
    public ApiResponse<List<MerchantBarberView>> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(barbers.list(principal));
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(summary = "新增本店理发师及服务绑定", description = "默认停用、理发师版本 0。姓名去首尾空白后 1～40 码点，允许重名；serviceIds 必填，0～50 个不同本店 HAIRCUT 服务字符串 ID，可绑定下架服务。"
            + "同事务创建人员与关系，任一失败整体回滚；重复提交会创建不同人员，不自动去重。不接受 enabled、账号或归属字段，不自动生成排班。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "创建成功", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<MerchantBarberView>> create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                                @Valid @RequestBody BarberCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(barbers.create(principal, request)));
    }

    @PatchMapping(value = "/{barberId}", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改本店理发师姓名与启停", description = "expectedVersion 使用理发师版本，与绑定接口共用。省略可变字段保持，null 拒绝，enabled 为严格布尔。"
            + "实际变化才加版本；无变化仍检查权限/版本。与按日排班共用人员版本；停用不清空绑定和排班，允许启用空服务人员，启用不等于可预约。已有未结束预约时，停用人员将返回409 BOOKING_CONFLICT；已接入存量预约保护。")
    public ApiResponse<MerchantBarberView> patch(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店理发师 ID") @PathVariable String barberId, @Valid @RequestBody BarberPatchRequest request) {
        return ApiResponse.success(barbers.patch(principal, barberId, request));
    }

    @PutMapping(value = "/{barberId}/services", consumes = "application/json", produces = "application/json")
    @Operation(summary = "完整替换理发师可提供服务", description = "传 0～50 个不同本店 HAIRCUT 服务 ID 的完整目标集合，空数组解除全部绑定；遗漏服务会解绑。"
            + "可绑定下架服务。与姓名/启停共用理发师版本，集合顺序不同不算变化，任一错误整体回滚；不改变人员启停或店铺/服务版本。解除仍被未结束预约使用的绑定将返回409 BOOKING_CONFLICT；已接入存量预约保护。")
    public ApiResponse<MerchantBarberView> replace(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店理发师 ID") @PathVariable String barberId, @Valid @RequestBody BarberServicesRequest request) {
        return ApiResponse.success(barbers.replaceServices(principal, barberId, request));
    }
}
