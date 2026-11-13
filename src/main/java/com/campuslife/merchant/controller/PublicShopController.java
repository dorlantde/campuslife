package com.campuslife.merchant.controller;

import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.service.PublicShopCatalogService;
import com.campuslife.merchant.service.BookingSlotsService;
import com.campuslife.merchant.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/shops")
@Tag(name = "03 商家入驻")
public class PublicShopController {
    private final PublicShopCatalogService shops;
    private final BookingSlotsService slots;
    public PublicShopController(PublicShopCatalogService shops, BookingSlotsService slots) { this.shops = shops; this.slots = slots; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "分页查询公开商家", description = "允许匿名；携带无效 token 仍失败。只展示 APPROVED 且 OWNER/经营账号有效的店铺，停业或分类字典停用不隐藏。名称包含搜索、分类交集筛选，固定 id ASC。"
            + "openNow 仅表示手动开关与上海当前营业时段，不代表预约空位；rating/reviewCount来自全部VISIBLE评价，平均分十进制HALF_UP保留两位，无公开评价null/0；同一RR快照，列表按页内店铺批量统计，不以作者当前启用状态过滤。")
    public ApiResponse<ShopPage> list(
            @Parameter(description = "仅搜店名，去首尾空白后最多 80 码点；空白不筛选，%/_ 按字面匹配") @RequestParam(required = false) String keyword,
            @Parameter(description = "正十进制分类 ID；合法但不存在返回空页") @RequestParam(required = false) String categoryId,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "2147483647", defaultValue = "1")) @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(required = false) String size) {
        return ApiResponse.success(shops.list(keyword, categoryId, page, size));
    }

    @GetMapping(value = "/{shopId}", produces = "application/json")
    @Operation(summary = "读取公开商家详情", description = "匿名可读。可见资格与列表相同；不可见与不存在统一 404。不公开账号、成员、审核或店铺版本信息。返回整周营业时间，上海本地营业区间左闭右开；rating/reviewCount来自全部VISIBLE评价，平均分十进制HALF_UP保留两位，无公开评价null/0；同一RR快照，列表按页内店铺批量统计，不以作者当前启用状态过滤。")
    public ApiResponse<ShopDetail> detail(@Parameter(description = "正十进制店铺 ID") @PathVariable String shopId) {
        return ApiResponse.success(shops.detail(shopId));
    }

    @GetMapping(value="/{shopId}/reviews",produces="application/json")
    @Operation(summary="分页查询公开商家评价",description="匿名GET，仅公开可见店铺，不可见或不存在统一404；携带无效token仍拒绝。只返回本店VISIBLE的BOOKING/PRINT历史评价，createdAt DESC、id DESC；默认page=1/size=20，最多100，超出末页items=[]。仅reviewId/rating/content/reviewerNickname/createdAt五字段，无学生/订单ID、登录名或审核原因。显示作者当前昵称，改昵称影响历史展示；停用/角色变化不自动隐藏。HIDDEN不展示或统计，管理员隐藏已接入并保存首次审核记录。短只读RR使店铺资格、total、items和昵称同快照，后续请求看到新提交/隐藏/改名；不取业务锁或写入。")
    public ApiResponse<PublicReviewPage> reviews(
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}")) @PathVariable String shopId,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="2147483647",defaultValue="1")) @RequestParam(required=false) String page,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="20")) @RequestParam(required=false) String size){
        return ApiResponse.success(shops.reviews(shopId,page,size));
    }

    @GetMapping(value = "/{shopId}/services", produces = "application/json")
    @Operation(summary = "查询公开店铺上架服务", description = "匿名可读，仅本店 enabled=true，按创建时间及 ID 倒序。可见店铺无匹配返回 []，不可见店铺 404；停业仍可浏览。PRINT 金额为整数分的参考起价、时长 null，不代表规格报价。服务版本不预留价格。")
    public ApiResponse<List<ServiceView>> services(@PathVariable String shopId,
            @Parameter(schema = @Schema(allowableValues = {"HAIRCUT", "PRINT"}), description = "可省略，合法类型无匹配返回 []") @RequestParam(required = false) String serviceType) {
        return ApiResponse.success(shops.services(shopId, serviceType));
    }

    @GetMapping(value = "/{shopId}/barbers", produces = "application/json")
    @Operation(summary = "查询公开店铺理发师", description = "匿名可读，店铺资格与公开目录相同。仅本店启用且至少绑定一项本店上架 HAIRCUT 服务的人员，创建时间/ID 倒序。"
            + "serviceId 可省略；传入时须为本店上架理发服务，不存在/跨店/下架/非理发统一 404；合法服务无人员返回 []。"
            + "serviceIds 始终为人员全部本店上架理发服务、数值升序，不随筛选截断。不返回人员版本、启停或排班。"
            + "可见 PRINT 店铺不传筛选返回 []；停业、无营业时间或排班仍可展示，不表示有预约空位。")
    public ApiResponse<List<PublicBarberView>> barbers(
            @Parameter(description = "正十进制店铺 ID") @PathVariable String shopId,
            @Parameter(description = "可省略；正十进制本店上架理发服务 ID，空值或非法格式 400") @RequestParam(required = false) String serviceId) {
        return ApiResponse.success(shops.barbers(shopId, serviceId));
    }

    @GetMapping(value = "/{shopId}/booking-slots", produces = "application/json")
    @Operation(summary = "查询预约候选时段（含真实占用）", description = "按配置与排班计算并排除 HELD/ACTIVE/CONSUMED 真实订单占用，不创建订单或预留资源；过期但尚未提交关闭的 HELD 仍保守占用。匿名可读，店铺须公开可见且 HAIRCUT，服务为本店上架理发，人员启用且绑定目标服务；无效资源组合统一 404。"
            + "date 为上海时区今天至第 6 天，起点按 15 分钟对齐且 >= 服务端单次 now+2小时，等于允许；完整时长须位于同一段营业/排班交集，不跨午休或午夜。"
            + "停业或无时段返回 slots=[]，仍含当前服务版本/价格/时长。当前关门不影响未来营业日候选，不依赖 openNow。返回 UTC 开始/结束时刻，候选可互相重叠；创建待支付预约仍在锁内重新校验占用、金额和配置。")
    public ApiResponse<BookingSlotsView> slots(
            @Parameter(description = "正十进制理发店 ID") @PathVariable String shopId,
            @Parameter(description = "必填，本店上架理发服务 ID") @RequestParam String serviceId,
            @Parameter(description = "必填，启用且绑定所选服务的本店人员 ID") @RequestParam String barberId,
            @Parameter(description = "严格 YYYY-MM-DD，校园今天至第 6 天", example = "2026-10-22") @RequestParam String date) {
        return ApiResponse.success(slots.query(shopId, serviceId, barberId, date));
    }
}
