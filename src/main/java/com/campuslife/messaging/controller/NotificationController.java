package com.campuslife.messaging.controller;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.messaging.service.NotificationService;
import com.campuslife.messaging.vo.NotificationPage;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("mysql") @Tag(name="11 本人通知",description="正常学生本人90天内的历史通知；不代表订单当前状态") @SecurityRequirement(name="bearerAuth")
public class NotificationController {
 private final NotificationService service;public NotificationController(NotificationService service){this.service=service;}
 @GetMapping("/api/v1/notifications") @Operation(summary="分页查询本人通知",description="page默认1，size默认20最大100，read仅true/false；落库时间/id倒序，同一只读快照。90天边界排除；GET不标已读。")
 public ApiResponse<NotificationPage> list(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@RequestParam(required=false) String read,@RequestParam(required=false) String page,@RequestParam(required=false) String size){return ApiResponse.success(service.list(p,read,page,size));}
 @PatchMapping("/api/v1/notifications/{notificationId}/read") @Operation(summary="标记本人通知已读",description="不填请求体。重复保留首次时间，跨账号/不存在/90天外404，账号及会话锁后复核。")
 public ApiResponse<Void> mark(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@PathVariable String notificationId,@RequestBody(required=false) String body){service.mark(p,notificationId,body);return ApiResponse.success(null);}
}
