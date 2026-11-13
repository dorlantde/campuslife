package com.campuslife.order.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.PrintQuoteRequest;
import com.campuslife.order.service.PrintQuoteService;
import com.campuslife.order.vo.PrintQuote;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="04 文件与打印报价",description="学生私有PDF上传、本人元数据/下载与打印报价；商家按本店有效打印任务受控下载")
@SecurityRequirement(name="bearerAuth")
public class PrintQuoteController {
    private final PrintQuoteService service;
    public PrintQuoteController(PrintQuoteService service){this.service=service;}
    @PostMapping(value="/api/v1/print-quotes",consumes="application/json",produces="application/json")
    @Operation(summary="计算本人PDF打印报价",description="正常STUDENT使用本人有效PDF，A4、BW/COLOR、SINGLE/DOUBLE、严格整数1～100份。服务须为可见PRINT店铺的上架PRINT服务及启用规格，规格缺失/停用409；不使用参考起价。按印刷面计算整数分，双面每份向上取整算纸张、不减半金额，最大50亿分。停业/休息/无营业时间仍可估价。同一只读RR快照，结束读取后统一校验身份与文件期限；不锁价、不续期、不读取磁盘或创建订单，不代表可下单。")
    public ApiResponse<PrintQuote> quote(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody PrintQuoteRequest body){return ApiResponse.success(service.quote(principal,body));}
}
