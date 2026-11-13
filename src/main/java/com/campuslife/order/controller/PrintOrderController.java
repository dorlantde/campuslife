package com.campuslife.order.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.PrintCreate;
import com.campuslife.order.service.PrintOrderService;
import com.campuslife.order.vo.OrderDetail;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="05 学生订单")
@SecurityRequirement(name="bearerAuth")
public class PrintOrderController {
    private final PrintOrderService service;
    public PrintOrderController(PrintOrderService service){this.service=service;}
    @PostMapping(value="/api/v1/orders/prints",consumes="application/json",produces="application/json")
    @Operation(summary="创建待支付打印订单",description="仅正常学生使用本人有效PDF，须当前上海营业时段。复制报价priceVersion/金额；1～100份，按印刷面计价。首单201，同操作同键同归一化内容200并带Idempotency-Replayed:true；异内容409。15分钟未支付关闭，同文件任一订单未终结继续保留，最后终结后保留7天。本人模拟支付、待接单取消和超时退款已支持；打印商家履约后续开放。")
    public ResponseEntity<ApiResponse<OrderDetail>> create(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(required=true,description="1～64位ASCII字母/数字/-/_，打印和预约创建分别隔离",example="print-demo-001") @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody PrintCreate body){
        var result=service.create(principal,key,body);var response=ResponseEntity.status(result.replayed()?200:201);
        if(result.replayed()){response.header("Idempotency-Replayed","true");}
        return response.body(ApiResponse.success(result.order()));
    }
}
