package com.campuslife.order.controller;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.BookingRejection;
import com.campuslife.order.dto.BookingCancellation;
import com.campuslife.order.service.BookingOrderService;
import com.campuslife.order.vo.OrderDetail;
import com.campuslife.order.vo.OrderPage;
import com.campuslife.order.vo.OrderEventView;
import java.util.List;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="07 商家订单履约",description="本店预约/打印查询、确认、未开始取消及开始；打印标待取件，预约完成")
@SecurityRequirement(name="bearerAuth")
public class MerchantBookingOrderController {
    private final BookingOrderService service;
    public MerchantBookingOrderController(BookingOrderService service) { this.service=service; }
    @GetMapping(value="/api/v1/merchant/orders",produces="application/json")
    @Operation(summary="分页查询本店订单",description="仅正常MERCHANT及本人店铺ACTIVE OWNER，归属由服务端固定；无所属店铺404，无效成员403。暂停/停业、服务下架或学生停用仍可读历史，接单仍遵循原资格规则。支持六种主状态，默认page=1、size=20，最多100条，按createdAt DESC、id DESC返回冻结摘要。资格、total与items来自同一只读RR快照，快照后成员停用由下次请求拒绝；GET不自动退款关单或推断到期状态。省略类型查询BOOKING与PRINT；可分别筛选，打印摘要包含printingProgress且预约时间为null。不附加学生个人信息、备注、事件、版本或完整资金对象，详情使用原接口。")
    public ApiResponse<OrderPage> list(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(allowableValues={"BOOKING","PRINT"}),description="省略查询本店两类订单；BOOKING/PRINT分别筛选，显式空值非法") @RequestParam(required=false) String orderType,
            @Parameter(schema=@Schema(allowableValues={"PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED"}),description="省略不筛选；精确大写，显式空值非法") @RequestParam(required=false) String status,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="2147483647",defaultValue="1")) @RequestParam(required=false) String page,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="20")) @RequestParam(required=false) String size) {
        return ApiResponse.success(service.merchantList(principal,orderType,status,page,size));
    }
    @GetMapping(value="/api/v1/merchant/orders/{orderId}",produces="application/json")
    @Operation(summary="查看本店订单",description="正常MERCHANT及本店ACTIVE OWNER；其他店铺、不存在或不支持的订单类型统一404。可读各状态历史订单，店铺暂停仍可读，GET不自动退款。返回冻结快照及真实支付/退款，不额外暴露学生账号信息。")
    public ApiResponse<OrderDetail> detail(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId) {
        return ApiResponse.success(service.merchantDetail(principal,orderId));
    }
    @GetMapping(value="/api/v1/merchant/orders/{orderId}/events",produces="application/json")
    @Operation(summary="查看本店订单事件",description="仅正常MERCHANT及本店ACTIVE OWNER，先核对订单归属再读事件；跨店/不存在/不支持类型统一404，本店无效成员403。暂停/停业、服务下架或学生停用仍可读旧单。按eventSeq升序返回完整原事件数组，包含实际操作者类型/ID和已保存原因（含管理员核实原因），不附加账号姓名/登录名/联系方式。RR内资格、订单与事件同一快照，快照后成员停用下次请求拒绝。GET不自动关单退款，不生成资金/版本/事件，不受模拟资金开关限制；授权无事件返回[]。")
    public ApiResponse<List<OrderEventView>> events(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}"),description="规范正BIGINT字符串，不接受前导零/符号/空白/溢出") @PathVariable String orderId) {
        return ApiResponse.success(service.merchantEvents(principal,orderId));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/accept",produces="application/json")
    @Operation(summary="接受本店已付款订单",description="不填请求体。正常MERCHANT、ACTIVE OWNER及APPROVED店铺，锁后严格早于接单截止；BOOKING还须早于预约开始。成功ACCEPTED/PAID，预约占用ACTIVE，打印进度QUEUED并保持文件保护。首次打印接单复核文件受保护元数据，不读取磁盘；重复接单仍须当前APPROVED，不改acceptedAt/版本/事件。手动停业/下架不阻止旧单接单。到期先全额退款关闭后409 ACCEPTANCE_DEADLINE_PASSED；新退款受模拟资金开关保护。合法接单不生成新资金，无需开启模拟资金。")
    public ApiResponse<OrderDetail> accept(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody(required=false) String body) {
        return ApiResponse.success(service.accept(principal,orderId,body));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/reject",consumes="application/json",produces="application/json")
    @Operation(summary="拒绝本店待接单订单并全额退款",description="正常MERCHANT及ACTIVE OWNER，暂停店铺仍可退款处理旧单。reason去首尾空白1～300码点；截止前全额模拟退款并以MERCHANT_REJECT关闭；预约释放占用，打印ABORTED并按全部引用刷新文件保留期，同原因重放200，改原因或其他状态409。到期先按ACCEPTANCE_TIMEOUT退款再409 ACCEPTANCE_DEADLINE_PASSED。关闭模拟资金时新退款503 MOCK_PAYMENT_DISABLED，原事实可重放。已接单未开始的打印改用cancel入口；已接入本店有效任务内容下载、开始打印、待取件与学生确认取件。")
    public ApiResponse<OrderDetail> reject(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody BookingRejection body) {
        return ApiResponse.success(service.reject(principal,orderId,body));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/cancel",consumes="application/json",produces="application/json")
    @Operation(summary="取消本店无法履约的已接单订单",description="正常MERCHANT及ACTIVE OWNER，店铺暂停/停业仍可处理旧单。BOOKING仅ACCEPTED/PAID且锁后严格早于预约startAt，不受学生提前1小时限制；等于或超过开始时间409 CANCEL_WINDOW_CLOSED。PRINT仅ACCEPTED/PAID/QUEUED、acceptedAt非空且startedAt/readyAt/pickedUpAt为空；没有预约时间窗口，不再判断原30分钟接单期限。reason去首尾空白1～300码点；全额模拟退款、MERCHANT_UNAVAILABLE关闭，预约释放占用，打印ABORTED并按全部引用刷新文件期限。同原因重放200，不改退款/版本/事件/保留期；不同原因或其他状态409 ORDER_STATE_CONFLICT，待接单调用不代跑超时退款。新退款需显式开启模拟资金，否则503 MOCK_PAYMENT_DISABLED；已有事实关闭开关后可重放。打印只复核文件保护元数据，不访问磁盘；本店有效任务的受控内容下载及打印履约已接入；开始后不支持普通取消。")
    public ApiResponse<OrderDetail> cancel(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody BookingCancellation body) {
        return ApiResponse.success(service.merchantCancel(principal,orderId,body));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/start",produces="application/json")
    @Operation(summary="开始本店预约或打印服务",description="无请求体。正常MERCHANT及ACTIVE OWNER可履行已接旧单，暂停/停业/下架不阻止。BOOKING首次ACCEPTED/PAID/ACTIVE，须startAt≤锁后now<endAt，否则409 BOOKING_TIME_INVALID；原预约占用保持。PRINT首次ACCEPTED/PAID/QUEUED、acceptedAt非空且startedAt/completedAt/readyAt/pickedUpAt空，now≥acceptedAt；成功IN_SERVICE/PRINTING、startedAt、版本+1及一次START事件。打印时钟倒退409 PRINT_TIME_INVALID；已PRINTING或READY_FOR_PICKUP的合法start重放不回退进度或覆盖时间。打印首动作复核文件保护元数据但不读磁盘，文件继续NULL保护。无新资金、不受资金开关限制；关闭不能恢复，合法COMPLETED/PAID/PICKED_UP可重放当前完成详情，不回退阶段；取件由学生确认。")
    public ApiResponse<OrderDetail> start(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody(required=false) String body) {
        return ApiResponse.success(service.fulfill(principal,orderId,false,body));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/ready-for-pickup",produces="application/json")
    @Operation(summary="标记本店打印待取件",description="无请求体。正常MERCHANT及本店ACTIVE OWNER，暂停仍可处理旧单；仅PRINT，BOOKING/跨店/不存在404。首次IN_SERVICE/PAID/PRINTING，startedAt及acceptedAt非空、completedAt/readyAt/pickedUpAt为空；锁后now≥startedAt，允许立即待取件，时钟倒退409 PRINT_TIME_INVALID。成功进度READY_FOR_PICKUP、readyAt、版本+1及一次同名MERCHANT事件，主状态仍IN_SERVICE，startedAt不改。重复保留原事实；不可跳过开始，合法COMPLETED/PAID/PICKED_UP重试200且保留原事实；关闭或错配状态409。首动作校验文件保护元数据但不读磁盘，无资金/期限写入，文件持续保护；学生可确认取件；管理员已可按版本核实未完成PRINT异常并全额模拟退款。")
    public ApiResponse<OrderDetail> ready(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody(required=false) String body) {
        return ApiResponse.success(service.readyForPickup(principal,orderId,body));
    }
    @PostMapping(value="/api/v1/merchant/orders/{orderId}/complete",produces="application/json")
    @Operation(summary="完成本店预约服务",description="不填请求体。正常MERCHANT及ACTIVE OWNER，仅本店BOOKING的IN_SERVICE/PAID/ACTIVE，不可跳过开始。now不得早于startedAt；可立即/提前或晚于预约结束完成。成功COMPLETED，写completedAt和一次COMPLETE事件；占用CONSUMED保留原区间不重新售卖，支付不变。重复完成200且不改时间/版本；暂停、资金开关关闭仍可处理合法旧单。已异常关闭不能完成；合法完成后学生可提交本人评价。")
    public ApiResponse<OrderDetail> complete(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String orderId,@RequestBody(required=false) String body) {
        return ApiResponse.success(service.fulfill(principal,orderId,true,body));
    }
}
