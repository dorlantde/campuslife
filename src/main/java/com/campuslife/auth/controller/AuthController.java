package com.campuslife.auth.controller;

import com.campuslife.auth.dto.LoginRequest;
import com.campuslife.auth.dto.RegisterRequest;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.service.LoginService;
import com.campuslife.auth.service.RegistrationService;
import com.campuslife.auth.service.SessionService;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.auth.vo.LoginView;
import com.campuslife.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/auth")
@Tag(name = "01 账号认证", description = "注册、登录和退出；测试账号为虚构业务账号")
public class AuthController {
    private final RegistrationService registration;
    private final LoginService login;
    private final SessionService sessions;

    public AuthController(RegistrationService registration, LoginService login, SessionService sessions) {
        this.registration = registration;
        this.login = login;
        this.sessions = sessions;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "注册学生账号", description = "创建 STUDENT / ACTIVE，version 为字符串 0；不自动登录。"
            + "成功 201；用户名已占用 409 / USERNAME_EXISTS；非法参数或未知字段 400。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "注册成功", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<AccountView>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(registration.register(request)));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "登录并取得 token", description = "成功后复制 data.accessToken，填入页面右上方 Authorize。"
            + "有效期 2 小时，各次登录为独立会话。用户名不存在或密码错误：401 / LOGIN_FAILED；账号停用：403。")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(login.login(request));
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "退出当前设备", description = "成功 200，data=null。仅撤销当前会话；未过期的同一 token 重复退出仍成功。")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        sessions.logout(principal);
        return ApiResponse.success(null);
    }
}
