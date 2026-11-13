package com.campuslife.coupon.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.coupon.dto.*;
import com.campuslife.coupon.service.MerchantCampaignService;
import com.campuslife.coupon.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/merchant/coupon-campaigns")
@Tag(name="09 商家优惠券活动",description="本店满减活动草稿、发布冻结与停止；学生领取后续接入")
@SecurityRequirement(name="bearerAuth")
public class MerchantCampaignController {
    private final MerchantCampaignService campaigns;
    public MerchantCampaignController(MerchantCampaignService campaigns){this.campaigns=campaigns;}

    @GetMapping(produces="application/json")
    @Operation(summary="分页查询本店优惠券活动",description="正常MERCHANT及ACTIVE OWNER，含全部三种状态与过期活动，暂停/停业仍可读。身份、成员、total/items同一短只读RR快照，返回前复核会话期限。创建时间/ID倒序；GET不写状态。")
    public ApiResponse<CampaignPage> list(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
        @Parameter(description="默认1，最大2147483647") @RequestParam(required=false) String page,
        @Parameter(description="默认20，范围1～100") @RequestParam(required=false) String size){
        return ApiResponse.success(campaigns.list(principal,page,size));
    }
    @GetMapping(value="/{campaignId}",produces="application/json")
    @Operation(summary="查询本店活动详情",description="短只读RR，暂停/停业仍可读，跨店/不存在404；包含首次发布/停止记录，不开放学生领取。")
    public ApiResponse<CampaignView> detail(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String campaignId){
        return ApiResponse.success(campaigns.detail(principal,campaignId));
    }
    @PostMapping(consumes="application/json",produces="application/json")
    @Operation(summary="创建本店优惠券活动草稿",description="正常商家/ACTIVE OWNER及APPROVED店铺；停业可维护。完整八字段，名称strip后1～80码点；1≤减额<门槛≤50亿分，总量1～100000；useStart≤claimStart<claimEnd≤useEnd。时间带偏移且精度最多毫秒；版本0。重复提交会创建新草稿，无创建幂等；不自动发布或发行券。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="草稿创建成功",useReturnTypeSchema=true)
    public ResponseEntity<ApiResponse<CampaignView>> create(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@RequestBody CampaignCreate body){
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(campaigns.create(principal,body)));
    }
    @PatchMapping(value="/{campaignId}",consumes="application/json",produces="application/json")
    @Operation(summary="完整修改本店活动草稿",description="须获批，八个业务字段完整替换而非省略保留，加expectedVersion。仅DRAFT；发布后全部冻结。无变化仍校验原版本，不写时间/版本；有变化独立活动版本+1，不修改店铺版本。")
    public ApiResponse<CampaignView> patch(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String campaignId,@RequestBody CampaignWrite body){
        return ApiResponse.success(campaigns.patch(principal,campaignId,body));
    }
    @PostMapping(value="/{campaignId}/publish",consumes="application/json",produces="application/json")
    @Operation(summary="发布并冻结活动",description="须获批，锁后当前时间严格早于claimEndAt，等于即拒绝；允许领取窗口已开始。保存首次操作者/时间/原版本，活动版本+1。同人同原版本、仍PUBLISHED才重放，不重新判窗口或写时间；停止后不重放发布。")
    public ApiResponse<CampaignView> publish(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String campaignId,@RequestBody CampaignVersion body){
        return ApiResponse.success(campaigns.transition(principal,campaignId,body,true));
    }
    @PostMapping(value="/{campaignId}/stop",consumes="application/json",produces="application/json")
    @Operation(summary="停止活动的新领取",description="正常商家/ACTIVE OWNER，暂停后仍可停止原PUBLISHED活动，无领取结束限制。保存首次处理记录，版本+1，不能恢复或修改权益。同人同原版本才重放，其余冲突；不会操作订单或资金，不撤回已成功领取的券。")
    public ApiResponse<CampaignView> stop(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String campaignId,@RequestBody CampaignVersion body){
        return ApiResponse.success(campaigns.transition(principal,campaignId,body,false));
    }
}
