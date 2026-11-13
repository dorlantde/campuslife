package com.campuslife.coupon.controller;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.service.CouponClaimService;
import com.campuslife.coupon.vo.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("mysql") @RequestMapping("/api/v1")
@Tag(name="10 学生优惠券",description="默认同步领取；受控ASYNC活动持久受理后返回202，本人查询最终结果")
public class CouponClaimController {
    private final CouponClaimService service;
    private final com.campuslife.coupon.service.CouponAdmissionService admission;
    public CouponClaimController(CouponClaimService service,com.campuslife.coupon.service.CouponAdmissionService admission){this.service=service;this.admission=admission;}
    @GetMapping("/shops/{shopId}/coupon-campaigns")
    @Operation(summary="匿名分页查询当前可领取活动",description="公开店铺资格，只列PUBLISHED且当前在领取窗口；停业保留，售罄保留并标剩余0。短RR及单次时刻，不预留库存。默认页1/大小20，最大100。")
    public ApiResponse<PublicCampaignPage> catalog(@PathVariable String shopId,@RequestParam(required=false) String page,@RequestParam(required=false) String size){return ApiResponse.success(service.catalog(shopId,page,size));}
    @PostMapping(value="/coupon-claims",consumes="application/json") @SecurityRequirement(name="bearerAuth")
    @Operation(summary="学生领取优惠券（同步或受控异步）",description="正常学生，独立Idempotency-Key与campaignId；SYNC按账号→会话→店铺S→gate S→活动X，将发行/券/成功请求同事务提交；ASYNC受理不取活动库存X锁。每活动每账号一次；原键同活动返回原事实，不重判活动期限/资格；换活动409。默认SYNC；受控ASYNC经SQL预检→事务外Lua→短SQL持久PENDING/outbox后返回202及Location，不保证得券。暂停/重建/Redis故障拒绝新受理，不降级同步。原键PENDING重放202，终态200。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="首次领取提交成功",useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="原终态请求重放",useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="异步PENDING/outbox已提交或原PENDING重放；不保证最终得券",headers={@io.swagger.v3.oas.annotations.headers.Header(name="Location",description="本人领取结果查询的相对地址",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string")),@io.swagger.v3.oas.annotations.headers.Header(name="Idempotency-Replayed",description="是否原键重放",schema=@io.swagger.v3.oas.annotations.media.Schema(type="boolean"))},useReturnTypeSchema=true)
    public ResponseEntity<ApiResponse<ClaimResult>> claim(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody CouponClaim body){var c=admission.claim(p,key,body);return ResponseEntity.status("PENDING".equals(c.result().status())?HttpStatus.ACCEPTED:c.replayed()?HttpStatus.OK:HttpStatus.CREATED).header("Idempotency-Replayed",Boolean.toString(c.replayed())).header(HttpHeaders.LOCATION,"/api/v1/coupon-claims/"+c.result().id()).body(ApiResponse.success(c.result()));}
    @GetMapping("/coupon-claims/{claimId}") @SecurityRequirement(name="bearerAuth")
    @Operation(summary="查询本人领取结果",description="正常学生本人短RR，跨账号/不存在404；读取已持久PENDING/SUCCEEDED/FAILED，PENDING不承诺获券，GET不触发超时或发行；停止/店铺失效仍能读旧事实。")
    public ApiResponse<ClaimResult> detail(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@PathVariable String claimId){return ApiResponse.success(service.detail(p,claimId));}
    @GetMapping("/me/coupons") @SecurityRequirement(name="bearerAuth")
    @Operation(summary="分页查询本人优惠券",description="身份、count/items同短RR，默认页1/20、每页最多100，领取时间/ID倒序；shopId/state可选。AVAILABLE到使用截止派生EXPIRED，GET不写。LOCKED/USED来自真实订单占用/核销，已付退款不返券。")
    public ApiResponse<OwnedCouponPage> own(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@RequestParam(required=false) String shopId,@RequestParam(required=false) String state,@RequestParam(required=false) String page,@RequestParam(required=false) String size){return ApiResponse.success(service.own(p,shopId,state,page,size));}
}
