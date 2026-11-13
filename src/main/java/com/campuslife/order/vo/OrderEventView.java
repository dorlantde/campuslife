package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="已授权预约或打印的原始业务事件；按eventSeq升序，不根据当前状态补写；不附带操作者账号资料")
public record OrderEventView(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}") String id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="0|[1-9][0-9]{0,18}",description="本订单内非负序号，CREATE从0开始；使用字符串") String eventSeq,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"CREATE","PAYMENT","ACCEPT","START","READY_FOR_PICKUP","PICKUP","COMPLETE","USER_CANCEL","PAYMENT_TIMEOUT","ACCEPTANCE_TIMEOUT","MERCHANT_REJECT","MERCHANT_UNAVAILABLE","ADMIN_EXCEPTION"}) String action,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,allowableValues={"PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED"},description="CREATE为null") String fromStatus,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED"}) String toStatus,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"STUDENT","MERCHANT","ADMIN","SYSTEM"}) String actorType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,description="实际操作者ID字符串，SYSTEM为null") String actorId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,maxLength=300,description="已保存的原原因码或商家/管理员填写的原因；未填写为null") String reason,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="原事件发生时刻，UTC") Instant occurredAt) {}
