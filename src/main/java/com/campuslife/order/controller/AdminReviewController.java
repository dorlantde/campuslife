package com.campuslife.order.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.ReviewHideWrite;
import com.campuslife.order.service.OrderReviewService;
import com.campuslife.order.vo.OwnReviewView;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="08 管理员")
@SecurityRequirement(name="bearerAuth")
public class AdminReviewController {
    private final OrderReviewService service;
    public AdminReviewController(OrderReviewService service){this.service=service;}
    @PostMapping(value="/api/v1/admin/reviews/{reviewId}/hide",consumes="application/json",produces="application/json")
    @Operation(summary="隐藏不适当评价",description="仅正常ADMIN及有效会话，严格仅reason字符串，strip后1～300个Unicode码点。VISIBLE→HIDDEN，原子保存首次管理员/原因/锁后UTC毫秒时间；时间早于createdAt拒绝409 REVIEW_TIME_INVALID。同管理员同归一化原因且审核记录完整的重试200，保留首次事实；不同管理员/原因或无审核记录的历史HIDDEN为409 REVIEW_STATE_CONFLICT。不要求expectedVersion，不恢复、编辑或删除。目标账号/店铺/OWNER停用仍可处理。后续公开分页和真实评分排除该记录，旧RR快照可完成；本人仍可读，reviewed仍true且不能重发。返回原六字段评价，不公开审核原因；不改订单版本/事件/资金/文件期限，不受模拟资金开关影响。")
    public ApiResponse<OwnReviewView> hide(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}")) @PathVariable String reviewId,
            @RequestBody ReviewHideWrite body){
        return ApiResponse.success(service.hide(principal,reviewId,body));
    }
}
