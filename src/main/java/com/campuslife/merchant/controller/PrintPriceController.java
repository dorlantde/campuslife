package com.campuslife.merchant.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.merchant.dto.PrintPricesRequest;
import com.campuslife.merchant.service.PrintPriceService;
import com.campuslife.merchant.vo.PrintPriceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/merchant/services/{serviceId}/print-prices")
@Tag(name = "06 商家经营配置")
@SecurityRequirement(name = "bearerAuth")
public class PrintPriceController {
    private final PrintPriceService prices;
    public PrintPriceController(PrintPriceService prices) { this.prices = prices; }

    @GetMapping(produces = "application/json")
    @Operation(summary = "查询本店打印服务价目", description = "要求正常 MERCHANT、APPROVED 店铺、ACTIVE OWNER 及本人 PRINT 服务。停业或服务下架仍可维护。"
            + "返回含停用规则的完整数组，无价目返回 []；顺序为 BW/SINGLE、BW/DOUBLE、COLOR/SINGLE、COLOR/DOUBLE，不分页。")
    public ApiResponse<List<PrintPriceView>> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店 PRINT 服务 ID") @PathVariable String serviceId) {
        return ApiResponse.success(prices.list(principal, serviceId));
    }

    @PutMapping(consumes = "application/json", produces = "application/json")
    @Operation(summary = "批量新增或修改本店打印价目", description = "一次 1～4 条不同 A4 规格，按规格新增/更新，省略规格保留；任一失败整批回滚。"
            + "单价为严格整数分/印刷面 1～100000，enabled 必填布尔值。新规格必须显式 expectedVersion=null，已有规格使用其价目版本字符串。"
            + "实际变更才增加价目版本，不更改店铺/服务版本或参考起价。返回保存后的完整价目数组，双面不自动半价；报价与订单另行开发。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"rules":[{"paperSize":"A4","colorMode":"BW","sideMode":"SINGLE","pricePerImpression":20,"enabled":true,"expectedVersion":null},
                              {"paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","pricePerImpression":20,"enabled":true,"expectedVersion":null}]}
                    """))))
    public ApiResponse<List<PrintPriceView>> save(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "本店 PRINT 服务 ID") @PathVariable String serviceId,
            @Valid @RequestBody PrintPricesRequest request) {
        return ApiResponse.success(prices.save(principal, serviceId, request));
    }
}
