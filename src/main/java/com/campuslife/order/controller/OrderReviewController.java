package com.campuslife.order.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.OrderReviewWrite;
import com.campuslife.order.service.OrderReviewService;
import com.campuslife.order.vo.OwnReviewView;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="05 学生订单")
@SecurityRequirement(name="bearerAuth")
public class OrderReviewController {
    private final OrderReviewService service;
    public OrderReviewController(OrderReviewService service){this.service=service;}
    @PostMapping(value="/api/v1/orders/{orderId}/review",consumes="application/json",produces="application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="评价本人已完成订单",description="仅正常STUDENT及有效会话，本人COMPLETED/PAID且合法完成的BOOKING/PRINT可评；BOOKING须CONSUMED，PRINT须PICKED_UP。整数1～5分，文字strip后1～1000个Unicode码点，仅rating/content两字段。完成后可立即评价、无截止期，锁后now不可早于completedAt。经营方停用/配置变化或PDF清理不影响旧单评价。首次201 VISIBLE，一单一次，隐藏也占用名额；相同或修改内容重复均409 REVIEW_ALREADY_EXISTS，响应丢失用GET确认。评价独立，不改订单状态/版本/事件/资金/占用或文件期限，详情reviewed读取真实记录。公开评价与真实评分统计已接入；管理员隐藏已接入，保留原事实及一单一次规则。")
    public ApiResponse<OwnReviewView> create(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}")) @PathVariable String orderId,@RequestBody OrderReviewWrite body){
        return ApiResponse.success(service.create(principal,orderId,body));
    }
    @GetMapping(value="/api/v1/orders/{orderId}/review",produces="application/json")
    @Operation(summary="查看本人订单评价",description="仅正常STUDENT及有效会话；同一短RR快照先校验当前身份与订单归属，再读取评价。未评价、他人或不存在404，不写入或自动关单。任何状态的本人BOOKING/PRINT可查询；隐藏后本人仍可见原内容与visibility，无账号资料。文件过期/清理或经营方资格变化不影响读取。")
    public ApiResponse<OwnReviewView> read(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}")) @PathVariable String orderId){
        return ApiResponse.success(service.read(principal,orderId));
    }
}
