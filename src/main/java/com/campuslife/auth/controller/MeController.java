package com.campuslife.auth.controller;

import com.campuslife.auth.dto.PasswordChangeRequest;
import com.campuslife.auth.dto.ProfileUpdateRequest;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.service.PasswordChangeService;
import com.campuslife.auth.service.ProfileUpdateService;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@Tag(name = "02 本人资料", description = "先登录并填写 Authorize，再读取或修改本人资料")
@SecurityRequirement(name = "bearerAuth")
public class MeController {
    private final PasswordChangeService passwords;
    private final ProfileUpdateService profiles;

    public MeController(PasswordChangeService passwords, ProfileUpdateService profiles) {
        this.passwords = passwords; this.profiles = profiles;
    }

    @GetMapping(value = "/api/v1/me", produces = "application/json")
    @Operation(summary = "读取本人资料和版本", description = "复制 data.version 作为修改昵称的 expectedVersion，保留字符串格式。未登录或会话失效返回 401。")
    public ApiResponse<AccountView> me(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(principal.account());
    }

    @PatchMapping(value = "/api/v1/me", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改本人昵称", description = "先 GET /me 读取版本。成功 200，返回最新资料；实际变更时 version 加 1。"
            + "遗漏昵称或归一化后相同不增加版本；版本过期返回 409 / CONFIG_VERSION_CONFLICT，重新读取后再决定是否修改。"
            + "null、非字符串、未知字段或非法版本返回 400；登录保持有效。")
    public ApiResponse<AccountView> update(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                          @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.success(profiles.update(principal, request));
    }

    @PostMapping(value = "/api/v1/me/password", consumes = "application/json", produces = "application/json")
    @Operation(summary = "修改密码并退出全部设备", description = "成功 200，data=null；当前及其他设备会话全部撤销，需要重新登录并更新 Authorize。"
            + "当前密码错误：400 / CURRENT_PASSWORD_INCORRECT；新旧密码均遵守注册密码规则。")
    public ApiResponse<Void> changePassword(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        passwords.change(principal, request);
        return ApiResponse.success(null);
    }
}
