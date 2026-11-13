package com.campuslife.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
public class OpenApiConfiguration {
    @Bean
    public OpenAPI campusLifeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CampusLife 本地接口调试").version("v1")
                        .description("虚构校园学习项目。先注册、登录，再把 data.accessToken 填入 Authorize。"
                                + "只填写 token，不需要添加 Bearer 前缀。修改昵称保留登录；修改密码撤销本人全部会话。"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("登录响应中的 data.accessToken；仅填写 token 本身。")));
    }

    @Bean
    public OpenApiCustomizer documentedErrors() {
        return api -> {
            // Register together with its references, after springdoc removes unreferenced input components.
            ObjectSchema failure = new ObjectSchema();
            failure.addProperty("code", new StringSchema().description("业务错误码，具体值见各 HTTP 状态说明"));
            failure.addProperty("message", new StringSchema().description("对外错误提示"));
            ObjectSchema emptyData = new ObjectSchema();
            emptyData.setNullable(true);
            failure.addProperty("data", emptyData);
            failure.addProperty("traceId", new StringSchema().description("与响应头 X-Trace-Id 相同的请求标识"));
            failure.setRequired(List.of("code", "message", "data", "traceId"));
            api.getComponents().addSchemas("ApiError", failure);
            // springdoc drops property-level nullable on this object reference. Inline the
            // generated shape locally so null is legal without weakening every owned coupon.
            Schema<?> claimResult = api.getComponents().getSchemas().get("ClaimResult");
            Schema<?> ownedCoupon = api.getComponents().getSchemas().get("OwnedCouponView");
            if (claimResult != null && ownedCoupon != null) {
                ObjectSchema nullableCoupon = new ObjectSchema();
                nullableCoupon.setNullable(true);
                nullableCoupon.setProperties(new java.util.LinkedHashMap<>(ownedCoupon.getProperties()));
                nullableCoupon.setRequired(ownedCoupon.getRequired());
                claimResult.getProperties().put("coupon", nullableCoupon);
            }

            Schema<?> emptyResponse = api.getComponents().getSchemas().get("ApiResponseVoid");
            if (emptyResponse != null) { emptyResponse.getProperties().get("data").setNullable(true); }
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                error(operation, "401", "AUTH_REQUIRED / TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED；登录还可能为 LOGIN_FAILED");
                error(operation, "406", "NOT_ACCEPTABLE：不支持请求的响应格式");
                error(operation, "500", "INTERNAL_ERROR：内部失败，不返回敏感细节");
                if (!path.endsWith("/health")) {
                    error(operation, "403", "ACCOUNT_DISABLED：账号停用");
                }
                if (operation.getRequestBody() != null) {
                    error(operation, "400", "VALIDATION_FAILED：请求格式错误；改密还可能为 CURRENT_PASSWORD_INCORRECT");
                    error(operation, "415", "UNSUPPORTED_MEDIA_TYPE：请使用 application/json");
                }
                if(path.startsWith("/api/v1/notifications")){error(operation,"400","VALIDATION_FAILED：分页、read、ID或请求体非法");error(operation,"404","RESOURCE_NOT_FOUND：本人通知不存在或过期");error(operation,"409","NOTIFICATION_TIME_INVALID：时间倒退");error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂不可用");}
                if (path.endsWith("/register") || path.endsWith("/merchant-applications")) {
                    operation.getResponses().remove("403");
                    error(operation, "409", "USERNAME_EXISTS：用户名已占用");
                } else if (path.endsWith("/shop-categories")) {
                    operation.getResponses().remove("403");
                } else if (path.equals("/api/v1/shops") || path.startsWith("/api/v1/shops/")) {
                    error(operation, "400", "VALIDATION_FAILED：ID、关键字、分页或服务类型格式/范围错误");
                    error(operation, "401", "TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED：携带无效凭证；不带凭证可匿名浏览");
                    if (!path.equals("/api/v1/shops")) { error(operation, "404", "RESOURCE_NOT_FOUND：店铺不存在或不符合公开可见资格"); }
                    if(List.of("/api/v1/shops","/api/v1/shops/{shopId}","/api/v1/shops/{shopId}/reviews").contains(path)){
                        error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂时竞争，读取失败不返回虚构的null/0；可重新请求");
                    }
                    if (path.endsWith("/barbers")) {
                        error(operation, "400", "VALIDATION_FAILED：shopId/serviceId 非法格式、空值或超出 BIGINT 范围");
                        error(operation, "404", "RESOURCE_NOT_FOUND：店铺不可见，或筛选服务不存在、跨店、下架、非理发");
                    }
                    if (path.endsWith("/booking-slots")) {
                        error(operation, "400", "VALIDATION_FAILED：必填 ID/date 格式、日期窗口或范围错误");
                        error(operation, "404", "RESOURCE_NOT_FOUND：店铺/服务/人员或绑定不存在、跨店、下架/停用、非理发");
                    }
                } else if (path.startsWith("/api/v1/coupon-claims") || path.equals("/api/v1/me/coupons")) {
                    error(operation,"400","VALIDATION_FAILED / IDEMPOTENCY_KEY_REQUIRED：领取仅campaignId及合法请求键；ID、state或分页非法");
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常学生本人");
                    error(operation,"404","RESOURCE_NOT_FOUND：活动不可见或本人领取结果不存在");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库竞争回滚，保留原键重试");
                    if(item.getPost()==operation){error(operation,"409","IDEMPOTENCY_KEY_CONFLICT / COUPON_ALREADY_CLAIMED / COUPON_NOT_CLAIMABLE / COUPON_SOLD_OUT / COUPON_TIME_INVALID：键冲突、已领、未开放/停止/过期、售罄或时间倒退");}
                } else if (path.equals("/api/v1/booking-quotes")) {
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常学生");
                    error(operation,"404","RESOURCE_NOT_FOUND：服务不可见或券非本人/跨店");
                    error(operation,"409","COUPON_UNAVAILABLE / COUPON_THRESHOLD_NOT_MET：券不可用或未达门槛");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库读取失败，可重新报价");
                } else if (path.equals("/api/v1/print-quotes")) {
                    error(operation,"400","VALIDATION_FAILED：六字段必填非null，规范ID/精确规格/严格整数1～100份；未知字段拒绝");
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常STUDENT计算本人文件报价");
                    error(operation,"404","RESOURCE_NOT_FOUND：文件不存在/他人/未发布，或店铺/服务不可见、下架、非PRINT");
                    error(operation,"410","FILE_EXPIRED：本人文件到期（等于即过期）或进入删除流程");
                    error(operation,"409","PRINT_SPEC_UNAVAILABLE / COUPON_UNAVAILABLE / COUPON_THRESHOLD_NOT_MET：规格未配置/停用，或券不可用/未达门槛");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂不可用；无业务写入可重新报价");
                    error(operation,"415","UNSUPPORTED_MEDIA_TYPE：请使用application/json");
                } else if (path.equals("/api/v1/files") || path.startsWith("/api/v1/files/")) {
                    error(operation, "400", "VALIDATION_FAILED：文件ID/文件名/空文件/表单格式错误；只允许一个file，无额外字段");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常STUDENT本人文件，商家/ADMIN暂无权限");
                    if (item.getPost()==operation) {
                        error(operation, "413", "FILE_TOO_LARGE：PDF最多20 MiB，multipart总大小最多22 MiB");
                        error(operation, "415", "FILE_TYPE_UNSUPPORTED / UNSUPPORTED_MEDIA_TYPE：须multipart/form-data及PDF文件");
                        error(operation, "422", "PDF_INVALID / PDF_ENCRYPTED / PDF_PAGE_LIMIT_EXCEEDED：无法解析、加密或超过500页");
                        error(operation, "429", "FILE_PROCESSING_BUSY：已有两个PDF正在处理");
                        error(operation, "503", "FILE_STORAGE_UNAVAILABLE / SERVICE_TEMPORARILY_UNAVAILABLE：文件保存或数据库暂不可用");
                    } else {
                        error(operation, "404", "RESOURCE_NOT_FOUND：不存在、他人或内部尚未发布文件");
                        error(operation, "410", "FILE_EXPIRED：本人文件已到期或进入删除流程");
                        if(path.endsWith("/content")) {
                            error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：正常学生本人或正常商家及ACTIVE OWNER；商家还须本店有效已接单打印关联，ADMIN无默认权限");
                            error(operation,"404","RESOURCE_NOT_FOUND：本人学生不可见，或商家无所属店铺/合格本店打印关联；文件保留不等于商家内容授权");
                            error(operation,"503","FILE_STORAGE_UNAVAILABLE / SERVICE_TEMPORARILY_UNAVAILABLE：对象不可读、存储根/长度异常或数据库暂不可用；写流后中断不追加JSON");
                            var success=operation.getResponses().get("200");
                            for(String name:List.of("Content-Disposition","Content-Length","Cache-Control","X-Content-Type-Options")) {
                                success.addHeaderObject(name,new io.swagger.v3.oas.models.headers.Header().schema(new StringSchema()));
                            }
                        }
                    }
                } else if (path.startsWith("/api/v1/orders")) {
                    error(operation, "400", "VALIDATION_FAILED / IDEMPOTENCY_KEY_REQUIRED：字段或请求键格式错误");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常学生可访问本人订单");
                    error(operation, "404", "RESOURCE_NOT_FOUND：资源不存在、跨店或不属于本人");
                    if (path.equals("/api/v1/orders")) {
                        error(operation, "400", "VALIDATION_FAILED：类型/主状态枚举或分页格式/范围错误");
                        operation.getResponses().remove("404");
                    }
                    if (path.endsWith("/events")) {
                        error(operation, "400", "VALIDATION_FAILED：orderId须为规范正BIGINT字符串，禁止前导零/符号/空白/溢出");
                    }
                    if (item.getPost() == operation) {
                        error(operation, "409", "COUPON_UNAVAILABLE / COUPON_THRESHOLD_NOT_MET / COUPON_TIME_INVALID / IDEMPOTENCY_KEY_CONFLICT / BOOKING_CONFLICT / QUOTE_CHANGED / SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE / BARBER_UNAVAILABLE / BOOKING_TIME_INVALID / ORDER_STATE_CONFLICT / CANCEL_WINDOW_CLOSED / PAYMENT_DEADLINE_PASSED / ACCEPTANCE_DEADLINE_PASSED");
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED：数据库竞争已回滚，或未启用模拟资金；已有资金事实可幂等重放");
                    }
                    if (path.endsWith("/payments/mock")) { operation.setRequestBody(null); }
                    if (path.endsWith("/payments/mock") || path.endsWith("/cancel")) {
                        error(operation,"503","FILE_STORAGE_UNAVAILABLE / SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED：打印文件保护元数据异常、数据库竞争回滚或新资金未开启；已有成功事实可重放");
                    }
                    if(path.endsWith("/pickup")){
                        operation.setRequestBody(null);operation.getResponses().remove("415");
                        error(operation,"400","VALIDATION_FAILED：规范orderId，无业务请求体");
                        error(operation,"404","RESOURCE_NOT_FOUND：不存在、他人或非PRINT订单");
                        error(operation,"409","ORDER_STATE_CONFLICT / PRINT_TIME_INVALID：须待取件或合法已完成重试，首次时间不可早于readyAt；无写入");
                        error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE / FILE_STORAGE_UNAVAILABLE：数据库竞争回滚、文件保护元数据或保留期写入异常，无新资金");
                    }
                    if(path.endsWith("/review")){
                        error(operation,"400","VALIDATION_FAILED：规范orderId；提交仅严格整数rating 1～5和strip后1～1000码点文字content，未知字段拒绝");
                        error(operation,"404","RESOURCE_NOT_FOUND：订单不存在、他人、不支持类型；GET尚未评价也404");
                        error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂时竞争回滚，可重试；若已提交用GET确认原评价");
                        if(item.getPost()==operation){
                            error(operation,"409","REVIEW_NOT_ALLOWED / REVIEW_ALREADY_EXISTS：未合法完成/付款、时钟早于完成或已有评价（含隐藏）；重复不改原内容");
                            operation.getResponses().remove("200");
                        }
                    }
                    if (path.endsWith("/prints")) {
                        error(operation,"409","COUPON_UNAVAILABLE / COUPON_THRESHOLD_NOT_MET / COUPON_TIME_INVALID / IDEMPOTENCY_KEY_CONFLICT / QUOTE_CHANGED / SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE / PRINT_SPEC_UNAVAILABLE");
                        error(operation,"410","FILE_EXPIRED：本人文件已到期或进入删除流程");
                        error(operation,"503","FILE_STORAGE_UNAVAILABLE / SERVICE_TEMPORARILY_UNAVAILABLE：存储不可读或数据库竞争回滚");
                    }
                    if (path.endsWith("/bookings") || path.endsWith("/prints")) {
                        operation.getParameters().stream().filter(parameter -> "Idempotency-Key".equals(parameter.getName()))
                                .forEach(parameter -> parameter.setRequired(true));
                        var success = operation.getResponses().get("200");
                        if (success != null) {
                            success.setDescription("同一创建键及归一化请求的原订单当前详情");
                            success.addHeaderObject("Idempotency-Replayed", new io.swagger.v3.oas.models.headers.Header()
                                    .description("重放时为 true").schema(new io.swagger.v3.oas.models.media.BooleanSchema()));
                            operation.getResponses().addApiResponse("201", new ApiResponse().description("首次创建成功，待支付暂占 15 分钟").content(success.getContent()));
                        }
                    }
                } else if (path.equals("/api/v1/merchant/orders")) {
                    error(operation, "400", "VALIDATION_FAILED：类型/主状态枚举或分页格式/范围错误");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常商家及ACTIVE OWNER；暂停店铺仍可读旧单");
                    error(operation, "404", "RESOURCE_NOT_FOUND：本人OWNER所属店铺不存在");
                } else if (path.startsWith("/api/v1/merchant/orders/")) {
                    error(operation, "400", "VALIDATION_FAILED：ID或原因格式错误；接单不接收请求体");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED：须正常商家及ACTIVE OWNER；接单还须APPROVED");
                    error(operation, "404", "RESOURCE_NOT_FOUND：订单不存在、非本店或该入口不支持的类型；complete仅BOOKING，ready-for-pickup仅PRINT");
                    if (path.endsWith("/events")) {
                        error(operation, "400", "VALIDATION_FAILED：orderId须为规范正BIGINT字符串，禁止前导零/符号/空白/溢出");
                        error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：须正常商家及本店ACTIVE OWNER；暂停店铺仍可读旧单");
                    }
                    if (item.getPost() == operation) {
                        error(operation, "409", "ORDER_STATE_CONFLICT / ACCEPTANCE_DEADLINE_PASSED / BOOKING_TIME_INVALID：状态冲突或已到期限；超时退款关闭提交后返回期限错误");
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED：数据库竞争回滚或新退款未开启模拟资金");
                    }
                    if (path.endsWith("/accept") || path.endsWith("/reject")) {
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED / FILE_STORAGE_UNAVAILABLE：数据库竞争回滚、新退款未开启模拟资金或打印文件保护元数据异常");
                    }
                    if (path.endsWith("/accept")) { operation.setRequestBody(null);operation.getResponses().remove("415"); }
                    if (path.endsWith("/cancel")) {
                        error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：正常商家及本店ACTIVE OWNER，暂停店铺仍可取消合法旧单");
                        error(operation, "409", "ORDER_STATE_CONFLICT / CANCEL_WINDOW_CLOSED：须已接单且未开始；BOOKING首次严格早于预约开始，PRINT按ACCEPTED/PAID/QUEUED及未开始时间字段判断，不受原接单截止限制；非法状态无写入");
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED / FILE_STORAGE_UNAVAILABLE：数据库竞争回滚、新退款未开启模拟资金或打印文件保护元数据/期限写入异常");
                    }
                    if (path.endsWith("/start") || path.endsWith("/complete")) {
                        operation.setRequestBody(null);operation.getResponses().remove("415");
                        error(operation, "400", "VALIDATION_FAILED：ID格式错误或携带禁止的业务请求体");
                        error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：须正常商家及本店ACTIVE OWNER；暂停店铺可处理已接单旧单");
                        error(operation, "409", "ORDER_STATE_CONFLICT / BOOKING_TIME_INVALID：状态不允许、首次开始不在预约区间或完成早于实际开始；无写入");
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE：数据库竞争已回滚，可重试原操作");
                    }
                    if(path.endsWith("/start")||path.endsWith("/ready-for-pickup")){
                        operation.setRequestBody(null);operation.getResponses().remove("415");
                        error(operation,"400","VALIDATION_FAILED：规范orderId，无业务请求体");
                        error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：正常商家及本店ACTIVE OWNER，暂停可处理旧单");
                        error(operation,"409",path.endsWith("/start")?"ORDER_STATE_CONFLICT / BOOKING_TIME_INVALID / PRINT_TIME_INVALID：非法状态、预约窗口或打印时钟倒退，无写入":"ORDER_STATE_CONFLICT / PRINT_TIME_INVALID：须已开始打印或合法重复，时钟不可早于startedAt");
                        error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE / FILE_STORAGE_UNAVAILABLE：数据库竞争回滚或打印文件保护元数据异常，无新资金");
                    }
                } else if (path.equals("/api/v1/merchant/shop") || path.equals("/api/v1/merchant/shop/resubmit") || path.equals("/api/v1/merchant/shop/business-hours")) {
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：非商家或账号停用");
                    error(operation, "404", "RESOURCE_NOT_FOUND：本人店铺不存在");
                    if (path.endsWith("/resubmit")) { error(operation, "409", "CONFIG_VERSION_CONFLICT / SHOP_STATE_CONFLICT：版本过期、达到上限或店铺/成员状态不允许重提"); }
                    else if (item.getPatch() == operation || path.endsWith("/business-hours")) {
                        error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED：非商家、账号/成员无效或店铺未获经营资格");
                        error(operation, "409", "CONFIG_VERSION_CONFLICT：店铺版本过期或实际变更时版本无法递增");
                        if (path.endsWith("/business-hours")) { error(operation, "409", "CONFIG_VERSION_CONFLICT / SCHEDULE_CONFLICT：版本过期、无法递增，或新营业时间无法覆盖今天及之后的已有排班（包含停用人员）"); }
                    }
                } else if (path.startsWith("/api/v1/merchant/coupon-campaigns")) {
                    error(operation,"400","VALIDATION_FAILED：规范ID/版本、完整八字段、Unicode名称、严格整数金额/总量、毫秒时间或窗口/分页错误；未知字段拒绝");
                    boolean requiresApproval=item.getPatch()==operation || (item.getPost()==operation&&!path.endsWith("/stop"));
                    error(operation,"403",requiresApproval?"ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED：仅正常商家/ACTIVE OWNER且店铺获批":"ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常商家及ACTIVE OWNER，暂停店铺仍可查询/停止旧活动");
                    error(operation,"404","RESOURCE_NOT_FOUND：无所属店铺，或活动不存在/跨店");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂时竞争，事务回滚；发布/停止保留原版本重试");
                    if(item.getPost()==operation||item.getPatch()==operation){
                        error(operation,"409","CONFIG_VERSION_CONFLICT / COUPON_CAMPAIGN_STATE_CONFLICT / COUPON_CLAIM_WINDOW_ENDED / COUPON_TIME_INVALID：版本、阶段、首次发布窗口或时间倒退冲突；无部分写入");
                    }
                    if(path.equals("/api/v1/merchant/coupon-campaigns")&&item.getPost()==operation){operation.getResponses().remove("200");}
                } else if (path.startsWith("/api/v1/merchant/services")) {
                    error(operation, "400", path.endsWith("/print-prices")
                            ? "VALIDATION_FAILED：非法规格、单价、批量格式或非 PRINT 服务"
                            : "VALIDATION_FAILED：非法字段、筛选、类型或金额/时长边界");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED：账号、成员或店铺不具经营资格");
                    error(operation, "404", "RESOURCE_NOT_FOUND：无本人店铺，或服务不存在/不属于本人店铺");
                    if (item.getPatch() == operation) { error(operation, "409", "CONFIG_VERSION_CONFLICT：服务版本过期或实际变更时无法递增"); }
                    if (path.endsWith("/print-prices") && item.getPut() == operation) {
                        error(operation, "409", "CONFIG_VERSION_CONFLICT：规格已存在、价目版本过期/不存在或实际变更时无法递增；整批回滚");
                    }
                } else if (path.startsWith("/api/v1/merchant/barbers")) {
                    error(operation, "400", "VALIDATION_FAILED：字段、姓名、ID/集合格式错误，或非 HAIRCUT 店铺/服务");
                    if (path.endsWith("/schedule")) { error(operation, "400", "VALIDATION_FAILED：日期、窗口、15 分钟时段格式/重叠、营业覆盖、版本/ID 格式错误或非 HAIRCUT 店铺"); }
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED：账号、成员或店铺不具经营资格");
                    error(operation, "404", "RESOURCE_NOT_FOUND：无本人店铺，或理发师/服务不存在或不属于本人");
                    if (item.getPatch() == operation || item.getPut() == operation) {
                        error(operation, "409", "CONFIG_VERSION_CONFLICT / BOOKING_CONFLICT / SCHEDULE_CONFLICT：版本冲突、无法递增或人员/绑定/排班仍保护未结束预约；失败整体回滚");
                    }
                } else if (path.startsWith("/api/v1/admin/accounts/") && path.endsWith("/status")) {
                    error(operation,"400","VALIDATION_FAILED：规范正BIGINT accountId，严格status/expectedVersion两字段，状态精确枚举、版本非负BIGINT字符串");
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅锁后当前正常ADMIN；可管理其他管理员");
                    error(operation,"404","RESOURCE_NOT_FOUND：目标账号不存在");
                    error(operation,"409","ADMIN_SELF_MODIFICATION_FORBIDDEN / CONFIG_VERSION_CONFLICT：禁止本人操作含同状态；旧版本或实际改变时版本无法递增");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：暂时数据库故障已回滚，请刷新列表确认状态/版本");
                } else if (path.equals("/api/v1/admin/accounts")) {
                    error(operation,"400","VALIDATION_FAILED：role/status须为精确枚举，page正整数至INT最大、size1～100；只接受数字且仅省略使用默认值");
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅当前正常ADMIN，可见本人/其他管理员/停用账号");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：数据库暂时故障，请重试；不会返回伪造空页");
                } else if (path.startsWith("/api/v1/admin/reviews/")) {
                    error(operation,"400","VALIDATION_FAILED：规范正BIGINT reviewId及仅含reason的严格JSON；strip后1～300个Unicode码点");
                    error(operation,"403","ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常ADMIN；目标作者/店铺停用仍可处理");
                    error(operation,"404","RESOURCE_NOT_FOUND：评价不存在");
                    error(operation,"409","REVIEW_STATE_CONFLICT / REVIEW_TIME_INVALID：首次处理人/原因不匹配、历史隐藏无审核记录，或首次时钟早于评价；不覆盖记录");
                    error(operation,"503","SERVICE_TEMPORARILY_UNAVAILABLE：暂时数据库故障已回滚，可用相同原因重试");
                } else if (path.startsWith("/api/v1/admin/orders/")) {
                    error(operation, "400", "VALIDATION_FAILED：ID、核实原因或版本字符串格式/范围错误");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：仅正常ADMIN；目标账号/店铺停用仍可核实旧单");
                    error(operation, "404", "RESOURCE_NOT_FOUND：订单不存在或非BOOKING/PRINT");
                    if (item.getPost() == operation) {
                        error(operation, "409", "CONFIG_VERSION_CONFLICT / ORDER_STATE_CONFLICT / PRINT_TIME_INVALID：订单版本已变化、状态不允许、版本无法递增、关闭重试不匹配或PRINT时钟早于已有阶段；无写入");
                        error(operation, "503", "SERVICE_TEMPORARILY_UNAVAILABLE / MOCK_PAYMENT_DISABLED / FILE_STORAGE_UNAVAILABLE：竞争回滚、新退款未开启模拟资金、PRINT文件保护或必要期限写入异常；原成功请求可重放");
                    }
                } else if (path.startsWith("/api/v1/admin/shops")) {
                    error(operation, "400", "VALIDATION_FAILED：参数或请求格式不符合要求");
                    error(operation, "403", "ROLE_FORBIDDEN / ACCOUNT_DISABLED：非管理员或账号停用");
                    if (!path.equals("/api/v1/admin/shops")) { error(operation, "404", "RESOURCE_NOT_FOUND：店铺不存在"); }
                    if (operation.getRequestBody() != null) { error(operation, "409", "CONFIG_VERSION_CONFLICT / SHOP_STATE_CONFLICT：版本过期、达到上限或状态不允许审核"); }
                } else if ("/api/v1/me".equals(path) && item.getPatch() == operation) {
                    error(operation, "409", "CONFIG_VERSION_CONFLICT：版本过期或不能递增，请重新 GET /me");
                } else if (path.endsWith("/password")) {
                    error(operation, "409", "ACCOUNT_CREDENTIALS_CHANGED：校验后凭据已变化，拒绝覆盖");
                }
            }));
        };
    }

    private static void error(io.swagger.v3.oas.models.Operation operation, String status, String description) {
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
    }
}
