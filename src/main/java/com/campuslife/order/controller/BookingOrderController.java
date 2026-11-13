package com.campuslife.order.controller;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.BookingCreate;
import com.campuslife.order.service.BookingOrderService;
import com.campuslife.order.vo.OrderDetail;
import com.campuslife.order.vo.PaymentResult;
import com.campuslife.order.vo.OrderPage;
import com.campuslife.order.vo.OrderEventView;
import java.util.List;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="05 学生订单",description="本人预约/打印、模拟支付、允许窗口取消、超时退款及学生本人评价；商家履约见07分组")
@SecurityRequirement(name="bearerAuth")
public class BookingOrderController {
    private final BookingOrderService service;
    public BookingOrderController(BookingOrderService service) { this.service=service; }
    @GetMapping(value="/api/v1/orders",produces="application/json")
    @Operation(summary="分页查询本人订单",description="仅正常STUDENT及有效会话，归属固定本人。按createdAt DESC、id DESC返回冻结摘要；支持六种主状态筛选，默认page=1、size=20，最多100条。总数和页内数据来自同一只读RR快照，超出末页返回空items；不自动关单/退款或推断到期状态。店铺/经营账号停用或配置变化不隐藏历史。默认包含本人BOOKING/PRINT，可按类型筛选；PRINT摘要的预约时间为null，进度为QUEUED/PRINTING/READY_FOR_PICKUP/PICKED_UP/ABORTED。不返回备注、核实原因、事件或完整资金对象，需查看详情。")
    public ApiResponse<OrderPage> list(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(allowableValues={"BOOKING","PRINT"}),description="省略查询本人两类订单；BOOKING/PRINT按类型筛选，显式空值非法") @RequestParam(required=false) String orderType,
            @Parameter(schema=@Schema(allowableValues={"PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED"}),description="省略不筛选；精确大写，显式空值非法") @RequestParam(required=false) String status,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="2147483647",defaultValue="1")) @RequestParam(required=false) String page,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="20")) @RequestParam(required=false) String size) {
        return ApiResponse.success(service.list(principal,orderType,status,page,size));
    }
    @PostMapping(value="/api/v1/orders/bookings",consumes="application/json",produces="application/json")
    @Operation(summary="创建待支付预约",description="仅 STUDENT。先查询候选，填写当前服务版本/金额与实际 UTC 开始时间。真实占用在日期保护行锁内复核；至少提前2小时，上海今天至第6天。首次201，相同键和归一化内容重放200，带 Idempotency-Replayed:true。15分钟待支付暂占；可模拟支付、取消或超时关闭。")
    public ResponseEntity<ApiResponse<OrderDetail>> create(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description="必填，1～64位字母/数字/-/_；重试保留原键，修改内容用新键",example="booking-demo-001",required=true)
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody BookingCreate body) {
        var result=service.create(principal,key,body);var response=ResponseEntity.status(result.replayed()?200:201);
        if (result.replayed()) { response.header("Idempotency-Replayed","true"); }
        return response.body(ApiResponse.success(result.order()));
    }
    @GetMapping(value="/api/v1/orders/{orderId}",produces="application/json")
    @Operation(summary="查看本人订单",description="仅 STUDENT，本人订单才可见，他人与不存在统一404。GET不自动关闭；到期而任务未处理时仍如实返回数据库状态和截止时间。金额、名称、时长为下单快照；payment/refund为真实成功事实，未发生为null。BOOKING的printing=null；PRINT返回冻结打印明细且booking=null。reviewed表示是否已有评价，隐藏评价也为true；不随订单version递增。")
    public ApiResponse<OrderDetail> detail(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId) {
        return ApiResponse.success(service.detail(principal,orderId));
    }
    @GetMapping(value="/api/v1/orders/{orderId}/events",produces="application/json")
    @Operation(summary="查看本人订单事件",description="仅正常STUDENT及本人BOOKING/PRINT，他人或不存在统一404。按eventSeq升序返回完整数组（CREATE从0开始），不是分页；包含原动作、前后状态、UTC时间、实际actorType/actorId及已保存原因，含管理员核实原因，SYSTEM的actorId为null。不附加姓名/登录名/联系方式。只读RR内核对归属和读取事件，快照后提交的动作下次可见；不自动关单退款、不补写或合成事件，不受模拟资金开关限制。经营资格变化不隐藏本人历史；授权订单无事件返回[]。")
    public ApiResponse<List<OrderEventView>> events(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}"),description="规范正BIGINT字符串，不接受前导零/符号/空白/溢出") @PathVariable String orderId) {
        return ApiResponse.success(service.events(principal,orderId));
    }
    @PostMapping(value="/api/v1/orders/{orderId}/cancel",produces="application/json")
    @Operation(summary="取消本人订单",description="PRINT待支付可直接取消，已付待接单可全额模拟退款，关闭后ABORTED；文件有其他未终结引用则继续保护，最后终结后保留7天。BOOKING待支付/待接单未到期限可取消；已接单理发距预约开始至少1小时可取消，等于允许，不足409 CANCEL_WINDOW_CLOSED。已付全额模拟退款，USER_CANCEL关闭；重复200且不重复流水/事件/文件期限。待支付/待接单到期先提交关闭/退款，再409 PAYMENT_DEADLINE_PASSED或ACCEPTANCE_DEADLINE_PASSED。新退款需显式开启模拟资金，否则503 MOCK_PAYMENT_DISABLED。PRINT已接单及后续状态学生不可直接取消，商家未开始取消或管理员异常按各自入口处理。")
    public ApiResponse<OrderDetail> cancel(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId) {
        return ApiResponse.success(service.cancel(principal,orderId));
    }
    @PostMapping(value="/api/v1/orders/{orderId}/pickup",produces="application/json")
    @Operation(summary="确认本人打印取件",description="仅正常STUDENT、有效会话及本人PRINT；BOOKING、他人或不存在统一404。不填请求体。首次须IN_SERVICE/PAID/READY_FOR_PICKUP且阶段时间完整，锁后now≥readyAt，允许立即确认；倒退409 PRINT_TIME_INVALID。成功COMPLETED/PAID/PICKED_UP，pickedUpAt=completedAt，订单版本+1及一次STUDENT PICKUP事件。合法重复200，保留原时间/版本/事件，即使文件后来清理也不重写。商家停用、暂停、停业或配置变化不阻止旧单取件，无新增资金、不受模拟开关限制。任何未终结文件引用继续保护；全部结束按最晚终结时间保留7天。完成单不再授权商家下载，已获准传输允许结束；取件不等下载或读取磁盘。")
    public ApiResponse<OrderDetail> pickup(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}"),description="规范正BIGINT字符串") @PathVariable String orderId,
            @RequestBody(required=false) String body) {
        return ApiResponse.success(service.pickup(principal,orderId,body));
    }
    @PostMapping(value="/api/v1/orders/{orderId}/payments/mock",produces="application/json")
    @Operation(summary="模拟支付本人预约或打印订单",description="仅正常STUDENT本人BOOKING/PRINT；不填写请求体、不接收客户端金额。15分钟内以冻结金额付款，首次/合法重复均200，同订单仅一笔成功支付。店铺须仍具经营资格，下单后手动停业/营业结束、服务或价目停用/改价不影响旧单。打印仅复核受保护文件元数据，不重新打开PDF，付款后进度仍QUEUED。支付后待接单30分钟，重复不延长期限或写新事件。到支付期限先提交未付关闭再409 PAYMENT_DEADLINE_PASSED；已退款/取消409 ORDER_STATE_CONFLICT。新资金默认关闭，显式启用后可用，否则503 MOCK_PAYMENT_DISABLED；开关关闭仍可重放已有成功事实。打印商家接单/文件访问/履约及学生取件已接入。")
    public ApiResponse<PaymentResult> pay(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,
            @RequestBody(required=false) String body) {
        return ApiResponse.success(service.pay(principal,orderId,body));
    }
}
