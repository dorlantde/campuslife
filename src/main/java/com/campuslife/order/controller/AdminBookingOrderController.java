package com.campuslife.order.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.BookingExceptionClose;
import com.campuslife.order.service.BookingOrderService;
import com.campuslife.order.vo.OrderDetail;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="08 管理员",description="使用受控创建的 ADMIN 业务账号登录；所有接口要求管理员身份")
@SecurityRequirement(name="bearerAuth")
public class AdminBookingOrderController {
    private final BookingOrderService service;
    public AdminBookingOrderController(BookingOrderService service) { this.service=service; }

    @GetMapping(value="/api/v1/admin/orders/{orderId}",produces="application/json")
    @Operation(summary="管理员查看预约或打印订单",description="仅正常ADMIN及有效会话，可查任意店铺各状态BOOKING/PRINT。目标账号/OWNER停用、店铺暂停不阻止核实旧单；不存在或不支持类型404。只读RR快照，不自动退款；返回冻结明细、版本、阶段时间及真实支付/退款。历史PDF过期/清理不隐藏订单，不读磁盘，不增加联系方式、存储位置或文件链接，也不授予ADMIN内容下载权限。")
    public ApiResponse<OrderDetail> detail(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId) {
        return ApiResponse.success(service.adminDetail(principal,orderId));
    }

    @PostMapping(value="/api/v1/admin/orders/{orderId}/close-exception",consumes="application/json",produces="application/json")
    @Operation(summary="管理员核实预约或打印异常并全额模拟退款关闭",description="仅正常ADMIN。提交reason及读取时的expectedVersion字符串，未知字段拒绝；仅PAID且PENDING_ACCEPTANCE/ACCEPTED/IN_SERVICE，已完成不能退款。BOOKING未开始RELEASED、开始后CONSUMED保留原区间。PRINT合法QUEUED/PRINTING/READY_FOR_PICKUP转CLOSED/REFUNDED/ABORTED，保留已有接单/开始/待取件时间，不填完成或取件时间；首次锁后now不得早于已发生阶段和付款，倒退409 PRINT_TIME_INVALID。原子记录全额退款、ADMIN_EXCEPTION、实际管理员及原因、公共版本和全部文件引用期限；有未结束引用仍NULL，全部终结后最晚终结+7天。首动作核对文件保护元数据，不读磁盘、不等下载。目标停用/店铺暂停仍可核实，无普通取消窗口。非关闭旧版本409 CONFIG_VERSION_CONFLICT，需重新核实；同管理员/原因/原版本且事实匹配重试200，不重复资金或续期，清理后仍可重放；其他关闭409。新退款受模拟开关保护，与扫描/取件先提交事实优先，不代跑到期关闭。关闭单不再授权商家下载，已获准传输可完成，ADMIN仍无内容权限。")
    public ApiResponse<OrderDetail> close(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody BookingExceptionClose body) {
        return ApiResponse.success(service.adminClose(principal,orderId,body));
    }
}
