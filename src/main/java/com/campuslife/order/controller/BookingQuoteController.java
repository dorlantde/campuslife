package com.campuslife.order.controller;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.order.dto.BookingQuoteRequest;
import com.campuslife.order.vo.BookingQuote;
import com.campuslife.order.service.BookingQuoteService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("mysql") @Tag(name="05 学生订单") @SecurityRequirement(name="bearerAuth")
public class BookingQuoteController {
 private final BookingQuoteService service;
 public BookingQuoteController(BookingQuoteService service){this.service=service;}
 @PostMapping(value="/api/v1/booking-quotes",consumes="application/json",produces="application/json")
 @Operation(summary="计算理发用券报价",description="正常学生选择上架理发服务与可选本人同店优惠券；同一只读RR快照计算原价、优惠与正实付。停业仍可估价，不锁券、不预留时段；下单重新校验时段、版本和金额。无效券不静默忽略，过期/已占用409，非本人或跨店404。")
 public ApiResponse<BookingQuote> quote(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal p,@RequestBody BookingQuoteRequest body){return ApiResponse.success(service.quote(p,body));}
}
