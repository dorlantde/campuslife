package com.campuslife;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class OpenApiMysqlIT {
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    @Test
    void generatedSpecificationDescribesOnlyImplementedOperationsAndExportsAnImportableSnapshot() throws Exception {
        var response = call("GET", "/v3/api-docs", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        ObjectNode spec = (ObjectNode) mapper.readTree(response.body());
        Files.createDirectories(Path.of("target","openapi"));
        Files.writeString(Path.of("target","openapi","observed-campuslife.openapi.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec),StandardCharsets.UTF_8);
        assertThat(spec.path("openapi").asString()).startsWith("3.0.");
        var claimSchema=spec.path("components").path("schemas").path("ClaimResult").path("properties");
        for(String field:List.of("completedAt","coupon","acceptedAt","deadline","failureCode")){
            assertThat(claimSchema.path(field).path("nullable").asBoolean()).as("Nullable persisted claim field %s",field).isTrue();
        }

        var tagNames=new java.util.ArrayList<String>();spec.path("tags").forEach(tag->tagNames.add(tag.path("name").asString()));
        assertThat(tagNames).hasSize(12).doesNotHaveDuplicates();
        assertThat(spec.path("paths").propertyNames()).containsExactlyInAnyOrder("/api/v1/system/health", "/api/v1/auth/register",
                "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/me", "/api/v1/me/password",
                "/api/v1/shop-categories", "/api/v1/merchant-applications", "/api/v1/merchant/shop",
                "/api/v1/admin/shops", "/api/v1/admin/shops/{shopId}", "/api/v1/admin/shops/{shopId}/approve", "/api/v1/admin/shops/{shopId}/reject",
                "/api/v1/merchant/shop/resubmit", "/api/v1/merchant/shop/business-hours", "/api/v1/merchant/services", "/api/v1/merchant/services/{serviceId}",
                "/api/v1/merchant/services/{serviceId}/print-prices", "/api/v1/merchant/barbers", "/api/v1/merchant/barbers/{barberId}",
                "/api/v1/merchant/barbers/{barberId}/services", "/api/v1/merchant/barbers/{barberId}/schedule",
                "/api/v1/shops", "/api/v1/shops/{shopId}", "/api/v1/shops/{shopId}/services", "/api/v1/shops/{shopId}/barbers", "/api/v1/shops/{shopId}/booking-slots", "/api/v1/shops/{shopId}/reviews",
                "/api/v1/booking-quotes", "/api/v1/print-quotes", "/api/v1/files", "/api/v1/files/{fileId}", "/api/v1/files/{fileId}/content", "/api/v1/orders", "/api/v1/orders/bookings", "/api/v1/orders/prints", "/api/v1/orders/{orderId}", "/api/v1/orders/{orderId}/events", "/api/v1/orders/{orderId}/review", "/api/v1/orders/{orderId}/pickup", "/api/v1/orders/{orderId}/cancel", "/api/v1/orders/{orderId}/payments/mock",
                "/api/v1/merchant/orders", "/api/v1/merchant/orders/{orderId}", "/api/v1/merchant/orders/{orderId}/events", "/api/v1/merchant/orders/{orderId}/accept", "/api/v1/merchant/orders/{orderId}/reject", "/api/v1/merchant/orders/{orderId}/cancel",
                "/api/v1/merchant/orders/{orderId}/start", "/api/v1/merchant/orders/{orderId}/ready-for-pickup", "/api/v1/merchant/orders/{orderId}/complete",
                "/api/v1/admin/orders/{orderId}", "/api/v1/admin/orders/{orderId}/close-exception", "/api/v1/admin/reviews/{reviewId}/hide", "/api/v1/admin/accounts", "/api/v1/admin/accounts/{accountId}/status",
                "/api/v1/merchant/coupon-campaigns", "/api/v1/merchant/coupon-campaigns/{campaignId}", "/api/v1/merchant/coupon-campaigns/{campaignId}/publish", "/api/v1/merchant/coupon-campaigns/{campaignId}/stop", "/api/v1/shops/{shopId}/coupon-campaigns", "/api/v1/coupon-claims", "/api/v1/coupon-claims/{claimId}", "/api/v1/me/coupons", "/api/v1/notifications", "/api/v1/notifications/{notificationId}/read");
        assertThat(spec.path("paths").properties().stream().mapToInt(entry -> entry.getValue().size()).sum()).isEqualTo(75);
        var bookingQuote=spec.path("paths").path("/api/v1/booking-quotes").path("post");
        assertThat(bookingQuote.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(bookingQuote.path("responses").path("409").toString()).contains("COUPON_UNAVAILABLE");
        assertThat(spec.path("components").path("schemas").path("BookingCreate").path("properties").has("userCouponId")).isTrue();
        assertThat(spec.path("components").path("schemas").path("OrderDetail").path("properties").path("coupon").toString()).contains("OrderCouponView");
        var campaign=spec.path("paths").path("/api/v1/merchant/coupon-campaigns");
        assertThat(campaign.path("post").path("responses").has("201")).isTrue();
        assertThat(campaign.path("post").path("responses").has("200")).isFalse();
        assertThat(campaign.path("get").path("description").asString()).contains("短只读RR","返回前");
        assertThat(campaign.path("get").path("responses").has("409")).isFalse();
        assertThat(campaign.path("get").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(spec.path("components").path("schemas").path("CampaignCreate").path("required").size()).isEqualTo(8);
        assertThat(spec.path("components").path("schemas").path("CampaignWrite").path("required").size()).isEqualTo(9);
        assertThat(spec.path("components").path("schemas").path("CampaignCreate").path("additionalProperties").asBoolean()).isFalse();
        var accountList=spec.path("paths").path("/api/v1/admin/accounts").path("get");
        assertThat(accountList.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(accountList.path("parameters").size()).isEqualTo(4);
        assertThat(accountList.path("description").asString()).contains("六字段","同一短只读RR快照","返回前");
        assertThat(accountList.path("responses").has("409")).isFalse();
        assertThat(accountList.path("responses").has("404")).isFalse();
        assertThat(accountList.path("responses").has("415")).isFalse();
        assertThat(accountList.path("responses").path("503").toString()).contains("SERVICE_TEMPORARILY_UNAVAILABLE");
        var accountStatus=spec.path("paths").path("/api/v1/admin/accounts/{accountId}/status").path("patch");
        assertThat(accountStatus.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(accountStatus.path("responses").path("409").toString()).contains("CONFIG_VERSION_CONFLICT","ADMIN_SELF_MODIFICATION_FORBIDDEN");
        assertThat(accountStatus.path("description").asString()).contains("双账号","恢复不复活旧token","原子提交");
        var statusWrite=spec.path("components").path("schemas").path("AccountStatusWrite");
        assertThat(statusWrite.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statusWrite.path("required").toString()).contains("status","expectedVersion");
        assertThat(statusWrite.path("properties").propertyNames()).containsExactlyInAnyOrder("status","expectedVersion");
        assertThat(spec.path("components").path("schemas").path("AdminAccountPage").path("properties").propertyNames()).containsExactlyInAnyOrder("items","page","size","total","totalPages");
        assertThat(spec.path("components").path("schemas").path("AccountView").path("properties").propertyNames()).containsExactlyInAnyOrder("id","username","nickname","role","status","version");
        var hide=spec.path("paths").path("/api/v1/admin/reviews/{reviewId}/hide").path("post");
        assertThat(hide.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(hide.path("responses").path("409").toString()).contains("REVIEW_STATE_CONFLICT","REVIEW_TIME_INVALID");
        assertThat(hide.path("responses").path("503").toString()).doesNotContain("MOCK_PAYMENT_DISABLED");
        var hideWrite=spec.path("components").path("schemas").path("ReviewHideWrite");
        assertThat(hideWrite.path("additionalProperties").asBoolean()).isFalse();
        assertThat(hideWrite.path("required").toString()).contains("reason");
        assertThat(hideWrite.path("properties").propertyNames()).containsExactly("reason");
        var publicReview=spec.path("paths").path("/api/v1/shops/{shopId}/reviews").path("get");
        assertThat(publicReview.has("security")).isFalse();
        assertThat(publicReview.path("parameters").size()).isEqualTo(3);
        assertThat(publicReview.path("responses").has("409")).isFalse();
        assertThat(publicReview.path("responses").path("503").toString()).contains("SERVICE_TEMPORARILY_UNAVAILABLE");
        assertThat(publicReview.path("description").asString()).contains("VISIBLE","当前昵称","同快照");
        assertThat(spec.path("components").path("schemas").path("PublicReviewView").path("properties").propertyNames()).containsExactlyInAnyOrder("reviewId","rating","content","reviewerNickname","createdAt");
        var review=spec.path("paths").path("/api/v1/orders/{orderId}/review");
        assertThat(review.path("post").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(review.path("post").path("responses").has("201")).isTrue();
        assertThat(review.path("post").path("responses").has("200")).isFalse();
        assertThat(review.path("post").path("responses").path("409").toString()).contains("REVIEW_NOT_ALLOWED","REVIEW_ALREADY_EXISTS").doesNotContain("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(review.path("get").path("responses").has("409")).isFalse();
        assertThat(review.path("get").path("responses").has("415")).isFalse();
        assertThat(review.path("post").path("responses").path("503").toString()).doesNotContain("MOCK_PAYMENT_DISABLED","FILE_STORAGE_UNAVAILABLE");
        var reviewWrite=spec.path("components").path("schemas").path("OrderReviewWrite");
        assertThat(reviewWrite.path("additionalProperties").asBoolean()).isFalse();
        assertThat(reviewWrite.path("required").toString()).contains("rating","content");
        assertThat(reviewWrite.path("properties").path("rating").path("type").asString()).isEqualTo("integer");
        assertThat(reviewWrite.path("properties").path("rating").path("minimum").asInt()).isEqualTo(1);
        assertThat(reviewWrite.path("properties").path("rating").path("maximum").asInt()).isEqualTo(5);
        assertThat(spec.path("components").path("schemas").path("OwnReviewView").path("properties").size()).isEqualTo(6);
        assertThat(spec.path("components").path("schemas").path("ShopSummary").path("properties").path("reviewCount").path("description").asString()).contains("VISIBLE");
        var pickup=spec.path("paths").path("/api/v1/orders/{orderId}/pickup").path("post");
        assertThat(pickup.has("requestBody")).isFalse();
        assertThat(pickup.path("parameters").size()).isEqualTo(1);
        assertThat(pickup.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(pickup.path("responses").propertyNames()).contains("200","400","401","403","404","409","503");
        assertThat(pickup.path("responses").path("409").toString()).contains("PRINT_TIME_INVALID").doesNotContain("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(pickup.path("responses").path("503").toString()).contains("FILE_STORAGE_UNAVAILABLE").doesNotContain("MOCK_PAYMENT_DISABLED");
        assertThat(pickup.path("responses").has("415")).isFalse();
        assertThat(spec.path("components").path("schemas").path("PrintingOrderView").path("properties").path("progress").toString()).contains("PICKED_UP");
        assertThat(spec.path("components").path("schemas").path("OrderEventView").path("properties").path("action").toString()).contains("READY_FOR_PICKUP","PICKUP");
        var print=spec.path("paths").path("/api/v1/orders/prints").path("post");
        assertThat(print.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(print.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(print.path("responses").propertyNames()).contains("201","200","400","401","403","404","409","410","415","503");
        assertThat(print.path("responses").path("200").path("headers").has("Idempotency-Replayed")).isTrue();
        assertThat(print.path("responses").path("409").toString()).contains("PRINT_SPEC_UNAVAILABLE","QUOTE_CHANGED");
        var printRequest=spec.path("components").path("schemas").path("PrintCreate");
        assertThat(printRequest.path("required").size()).isEqualTo(8);
        assertThat(printRequest.path("properties").path("expectedPayableAmount").path("maximum").asLong()).isEqualTo(5000000000L);
        assertThat(spec.path("components").path("schemas").path("PrintingOrderView").path("properties").size()).isEqualTo(12);
        assertThat(spec.path("components").path("schemas").path("FileView").path("properties").path("expiresAt").path("nullable").asBoolean()).isTrue();
        var list=spec.path("paths").path("/api/v1/orders").path("get");
        assertThat(list.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(list.path("parameters").size()).isEqualTo(4);
        assertThat(list.path("parameters").toString()).contains("orderType", "status", "page", "size", "2147483647");
        assertThat(list.path("responses").has("404")).isFalse();
        assertThat(list.path("responses").path("400").path("description").asString()).doesNotContain("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(spec.path("components").path("schemas").path("OrderPage").path("properties").path("items").path("items").path("$ref").asString()).endsWith("/OrderSummary");
        assertThat(spec.path("components").path("schemas").path("OrderSummary").path("properties").path("printingProgress").path("nullable").asBoolean()).isTrue();
        var merchantList=spec.path("paths").path("/api/v1/merchant/orders").path("get");
        assertThat(merchantList.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(merchantList.path("parameters").size()).isEqualTo(4);
        assertThat(merchantList.path("parameters").toString()).contains("orderType", "status", "page", "size", "2147483647");
        assertThat(merchantList.path("responses").path("403").path("description").asString()).contains("ROLE_FORBIDDEN").doesNotContain("SHOP_NOT_APPROVED");
        assertThat(merchantList.path("responses").has("404")).isTrue();
        assertThat(merchantList.path("responses").has("409")).isFalse();
        assertThat(merchantList.path("responses").has("503")).isFalse();
        assertThat(merchantList.path("responses").path("400").path("description").asString()).doesNotContain("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(merchantList.path("responses").path("200").toString()).contains("ApiResponseOrderPage");
        for(var eventPath:List.of("/api/v1/orders/{orderId}/events","/api/v1/merchant/orders/{orderId}/events")) {
            var eventOp=spec.path("paths").path(eventPath).path("get");
            assertThat(eventOp.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(eventOp.path("parameters").size()).isEqualTo(1);
            assertThat(eventOp.path("parameters").get(0).path("name").asString()).isEqualTo("orderId");
            assertThat(eventOp.path("responses").has("404")).isTrue();
            assertThat(eventOp.path("responses").has("409")).isFalse();
            assertThat(eventOp.path("responses").has("503")).isFalse();
            assertThat(eventOp.path("responses").path("400").path("description").asString()).doesNotContain("IDEMPOTENCY_KEY_REQUIRED");
            assertThat(eventOp.path("responses").path("403").path("description").asString()).doesNotContain("SHOP_NOT_APPROVED");
            var resultRef=eventOp.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asString();
            var envelope=spec.path("components").path("schemas").path(resultRef.substring(resultRef.lastIndexOf('/')+1));
            assertThat(envelope.path("properties").path("data").path("type").asString()).isEqualTo("array");
            assertThat(envelope.path("properties").path("data").path("items").path("$ref").asString()).endsWith("/OrderEventView");
        }
        var eventSchema=spec.path("components").path("schemas").path("OrderEventView");
        assertThat(eventSchema.path("properties").propertyNames()).containsExactlyInAnyOrder("id","eventSeq","action","fromStatus","toStatus","actorType","actorId","reason","occurredAt");
        assertThat(eventSchema.path("properties").path("eventSeq").path("type").asString()).isEqualTo("string");
        for(var nullable:List.of("fromStatus","actorId","reason")){assertThat(eventSchema.path("properties").path(nullable).path("nullable").asBoolean()).isTrue();}
        var quote=spec.path("paths").path("/api/v1/print-quotes").path("post");
        assertThat(quote.path("security").get(0).has("bearerAuth")).isTrue();
        for(String status:List.of("200","400","401","403","404","409","410","415","503")) {
            assertThat(quote.path("responses").path(status).path("content").has("application/json")).isTrue();
        }
        assertThat(quote.path("responses").path("409").path("description").asString()).contains("PRINT_SPEC_UNAVAILABLE");
        assertThat(quote.path("responses").path("200").toString()).contains("ApiResponsePrintQuote");
        var qr=spec.path("components").path("schemas").path("PrintQuoteRequest");
        assertThat(qr.path("additionalProperties").asBoolean()).isFalse();
        assertThat(qr.path("required").size()).isEqualTo(6);
        assertThat(qr.path("properties").path("copies").path("maximum").asInt()).isEqualTo(100);
        var qv=spec.path("components").path("schemas").path("PrintQuote");
        assertThat(qv.path("properties").size()).isEqualTo(17);
        assertThat(qv.path("properties").path("payableAmount").path("format").asString()).isEqualTo("int64");
        assertThat(qv.path("properties").path("payableAmount").path("maximum").asLong()).isEqualTo(5000000000L);
        var upload=spec.path("paths").path("/api/v1/files").path("post");
        assertThat(upload.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(upload.path("requestBody").path("content").has("multipart/form-data")).isTrue();
        assertThat(upload.path("requestBody").path("content").path("multipart/form-data").toString()).contains("file", "binary");
        assertThat(upload.path("responses").has("201")).isTrue();
        assertThat(upload.path("responses").has("413")).isTrue();
        assertThat(upload.path("responses").has("422")).isTrue();
        assertThat(upload.path("responses").has("429")).isTrue();
        var fileGet=spec.path("paths").path("/api/v1/files/{fileId}").path("get");
        assertThat(fileGet.path("responses").has("410")).isTrue();
        var download=spec.path("paths").path("/api/v1/files/{fileId}/content").path("get");
        assertThat(download.path("parameters").size()).isEqualTo(1);
        assertThat(download.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(download.path("responses").path("200").path("content").propertyNames()).containsExactly("application/pdf");
        assertThat(download.path("responses").path("200").path("content").path("application/pdf").path("schema").path("format").asString()).isEqualTo("binary");
        for(String status:List.of("400","401","403","404","410","503")) {
            assertThat(download.path("responses").path(status).path("content").has("application/json")).isTrue();
        }
        assertThat(download.path("responses").path("200").path("headers").propertyNames()).contains("Content-Disposition","Content-Length","Cache-Control","X-Content-Type-Options");
        var fileSchema=spec.path("components").path("schemas").path("FileView");
        assertThat(fileSchema.path("properties").propertyNames()).containsExactlyInAnyOrder("id","originalName","byteSize","mediaType","pageCount","status","expiresAt");
        assertThat(fileSchema.path("properties").path("pageCount").path("maximum").asInt()).isEqualTo(500);
        var adminClose=spec.path("paths").path("/api/v1/admin/orders/{orderId}/close-exception").path("post");
        assertThat(adminClose.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(adminClose.path("responses").path("409").path("description").asString()).contains("CONFIG_VERSION_CONFLICT", "ORDER_STATE_CONFLICT");
        assertThat(adminClose.path("responses").path("503").path("description").asString()).contains("MOCK_PAYMENT_DISABLED", "FILE_STORAGE_UNAVAILABLE");
        assertThat(adminClose.path("responses").path("409").toString()).contains("PRINT_TIME_INVALID");
        assertThat(adminClose.path("description").asString()).contains("PRINT", "ABORTED", "取件");
        var adminDetail=spec.path("paths").path("/api/v1/admin/orders/{orderId}").path("get");
        assertThat(adminDetail.path("description").asString()).contains("BOOKING/PRINT", "只读RR", "清理");
        assertThat(adminDetail.path("responses").has("409")).isFalse();
        var exception=spec.path("components").path("schemas").path("BookingExceptionClose");
        assertThat(exception.path("additionalProperties").asBoolean()).isFalse();
        assertThat(exception.path("required").toString()).contains("reason", "expectedVersion");
        assertThat(exception.path("properties").path("expectedVersion").path("type").asString()).isEqualTo("string");
        for (var action:List.of("start","complete")) {
            var fulfillment=spec.path("paths").path("/api/v1/merchant/orders/{orderId}/"+action).path("post");
            assertThat(fulfillment.has("requestBody")).isFalse();
            assertThat(fulfillment.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(fulfillment.path("responses").path("409").path("description").asString()).contains("BOOKING_TIME_INVALID");
            assertThat(fulfillment.path("responses").path("503").path("description").asString()).doesNotContain("MOCK_PAYMENT_DISABLED");
        }
        var cancel=spec.path("paths").path("/api/v1/merchant/orders/{orderId}/cancel").path("post");
        assertThat(cancel.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(cancel.path("responses").path("409").path("description").asString()).contains("CANCEL_WINDOW_CLOSED").doesNotContain("ACCEPTANCE_DEADLINE_PASSED");
        var cancellation=spec.path("components").path("schemas").path("BookingCancellation");
        assertThat(cancellation.path("additionalProperties").asBoolean()).isFalse();
        assertThat(cancellation.path("required").toString()).isEqualTo("[\"reason\"]");
        var acceptance=spec.path("paths").path("/api/v1/merchant/orders/{orderId}/accept").path("post");
        assertThat(acceptance.has("requestBody")).isFalse();
        assertThat(acceptance.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(acceptance.path("responses").path("409").path("description").asString()).contains("ACCEPTANCE_DEADLINE_PASSED");
        var rejection=spec.path("components").path("schemas").path("BookingRejection");
        assertThat(rejection.path("additionalProperties").asBoolean()).isFalse();
        assertThat(rejection.path("required").toString()).isEqualTo("[\"reason\"]");
        var payment=spec.path("paths").path("/api/v1/orders/{orderId}/payments/mock").path("post");
        assertThat(payment.has("requestBody")).isFalse();
        assertThat(payment.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(payment.path("responses").path("503").path("description").asString()).contains("MOCK_PAYMENT_DISABLED");
        assertThat(payment.path("summary").asString()).contains("打印");
        assertThat(payment.path("description").asString()).contains("BOOKING/PRINT","冻结金额","QUEUED");
        assertThat(payment.path("responses").path("503").path("description").asString()).contains("FILE_STORAGE_UNAVAILABLE");
        assertThat(spec.path("components").path("schemas").path("OrderDetail").path("properties").path("payment").toString()).contains("PaymentView");
        for (String path : List.of("/api/v1/shops", "/api/v1/shops/{shopId}", "/api/v1/shops/{shopId}/services", "/api/v1/shops/{shopId}/barbers", "/api/v1/shops/{shopId}/booking-slots")) {
            var publicGet = spec.path("paths").path(path).path("get");
            assertThat(publicGet.path("security").isMissingNode() || publicGet.path("security").isEmpty()).isTrue();
            assertThat(publicGet.path("responses").has("400")).isTrue();
            assertThat(publicGet.path("responses").path("401").path("description").asString()).doesNotContain("AUTH_REQUIRED");
        }
        var candidates = spec.path("paths").path("/api/v1/shops/{shopId}/booking-slots").path("get");
        assertThat(candidates.path("summary").asString()).contains("候选");
        assertThat(candidates.path("description").asString()).contains("真实订单占用");
        var creation = spec.path("paths").path("/api/v1/orders/bookings").path("post");
        assertThat(creation.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(creation.path("responses").has("201")).isTrue();
        assertThat(creation.path("responses").has("503")).isTrue();
        assertThat(creation.path("responses").path("200").path("headers").has("Idempotency-Replayed")).isTrue();
        assertThat(creation.path("parameters").get(0).path("name").asString()).isEqualTo("Idempotency-Key");
        assertThat(creation.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(candidates.path("parameters").size()).isEqualTo(4);
        for (var parameter : candidates.path("parameters")) { assertThat(parameter.path("required").asBoolean()).isTrue(); }
        assertThat(spec.path("components").path("schemas").path("BookingSlotsView").path("properties").propertyNames())
                .containsExactlyInAnyOrder("serviceVersion", "priceAmount", "durationMinutes", "slots");
        assertThat(spec.path("components").path("schemas").path("BookingSlot").path("properties").path("startAt").path("format").asString()).isEqualTo("date-time");
        var barber = spec.path("components").path("schemas").path("PublicBarberView");
        assertThat(barber.path("properties").propertyNames()).containsExactlyInAnyOrder("id", "displayName", "serviceIds");
        assertThat(barber.path("properties").path("serviceIds").path("items").path("type").asString()).isEqualTo("string");
        assertThat(barber.path("properties").path("serviceIds").path("minItems").asInt()).isEqualTo(1);
        assertThat(spec.path("paths").path("/api/v1/shops/{shopId}/barbers").path("get").path("parameters").toString()).contains("serviceId");
        assertThat(spec.path("components").path("schemas").path("ShopSummary").path("properties").propertyNames())
                .containsExactlyInAnyOrder("id", "name", "category", "locationText", "businessEnabled", "openNow", "rating", "reviewCount");
        assertThat(spec.path("components").path("schemas").path("ShopDetail").path("properties").has("approvalReason")).isFalse();
        assertThat(spec.path("components").path("schemas").path("ShopSummary").path("properties").path("rating").path("nullable").asBoolean()).isTrue();
        assertThat(spec.path("components").path("schemas").path("ServiceView").path("properties").path("durationMinutes").path("nullable").asBoolean()).isTrue();
        var schedule = spec.path("components").path("schemas").path("ScheduleWrite");
        assertThat(schedule.path("required").toString()).contains("date", "expectedBarberVersion", "intervals");
        assertThat(schedule.path("properties").path("intervals").path("maxItems").asInt()).isEqualTo(4);
        assertThat(spec.path("components").path("schemas").path("ScheduleInterval").path("properties").propertyNames()).containsExactlyInAnyOrder("startTime", "endTime");
        assertThat(spec.path("paths").path("/api/v1/merchant/barbers/{barberId}/schedule").path("get").path("parameters").toString()).contains("date", "required");
        assertThat(spec.path("paths").path("/api/v1/merchant/shop/business-hours").path("put").path("responses").path("409").path("description").asString()).contains("SCHEDULE_CONFLICT");
        var barberCreate = spec.path("components").path("schemas").path("BarberCreateRequest");
        assertThat(barberCreate.path("required").toString()).contains("displayName", "serviceIds").doesNotContain("enabled");
        assertThat(barberCreate.path("properties").path("serviceIds").path("type").asString()).isEqualTo("array");
        assertThat(barberCreate.path("properties").path("serviceIds").path("maxItems").asInt()).isEqualTo(50);
        assertThat(barberCreate.path("properties").path("serviceIds").path("items").path("type").asString()).isEqualTo("string");
        assertThat(spec.path("paths").path("/api/v1/merchant/barbers").path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/merchant/barbers/{barberId}/services").path("put").path("responses").has("409")).isTrue();
        assertThat(spec.path("components").path("schemas").path("BarberPatchRequest").path("required").toString()).isEqualTo("[\"expectedVersion\"]");
        var printPrices = spec.path("paths").path("/api/v1/merchant/services/{serviceId}/print-prices");
        assertThat(printPrices.path("get").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(printPrices.path("put").path("responses").has("409")).isTrue();
        var priceRule = spec.path("components").path("schemas").path("PrintPriceRuleRequest");
        assertThat(priceRule.path("required").size()).isEqualTo(6);
        assertThat(priceRule.path("properties").path("expectedVersion").path("nullable").asBoolean()).isTrue();
        assertThat(priceRule.path("properties").path("pricePerImpression").path("type").asString()).isEqualTo("integer");
        assertThat(priceRule.path("additionalProperties").asBoolean()).isFalse();
        var rules = spec.path("components").path("schemas").path("PrintPricesRequest").path("properties").path("rules");
        assertThat(rules.path("minItems").asInt()).isEqualTo(1);
        assertThat(rules.path("maxItems").asInt()).isEqualTo(4);
        var createService = spec.path("components").path("schemas").path("ServiceCreateRequest");
        assertThat(createService.path("required").toString()).contains("durationMinutes", "priceAmount", "serviceType", "name").doesNotContain("enabled", "description");
        assertThat(createService.path("properties").path("durationMinutes").path("nullable").asBoolean()).isTrue();
        assertThat(createService.path("properties").path("priceAmount").path("type").asString()).isEqualTo("integer");
        assertThat(spec.path("paths").path("/api/v1/merchant/services").path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/merchant/services/{serviceId}").path("patch").path("responses").has("409")).isTrue();
        var hours = spec.path("components").path("schemas").path("BusinessHoursRequest");
        assertThat(hours.path("properties").path("intervals").path("type").asString()).isEqualTo("array");
        assertThat(hours.path("properties").path("intervals").path("maxItems").asInt()).isEqualTo(28);
        assertThat(hours.path("required").toString()).contains("expectedVersion", "intervals");
        assertThat(spec.path("paths").path("/api/v1/merchant/shop/business-hours").path("put").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(spec.path("components").path("schemas").path("MerchantShopView").path("properties").path("businessHours").path("type").asString()).isEqualTo("array");
        var operation = spec.path("paths").path("/api/v1/merchant/shop").path("patch");
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(operation.path("parameters").isMissingNode()).isTrue();
        assertThat(operation.path("responses").has("409")).isTrue();
        var patch = spec.path("components").path("schemas").path("ShopPatchRequest");
        assertThat(patch.path("required").toString()).isEqualTo("[\"expectedVersion\"]");
        assertThat(patch.path("additionalProperties").asBoolean()).isFalse();
        for (String field : List.of("expectedVersion", "name", "locationText", "description")) {
            assertThat(patch.path("properties").path(field).path("type").asString()).isEqualTo("string");
        }
        assertThat(patch.path("properties").path("businessEnabled").path("type").asString()).isEqualTo("boolean");
        var resubmit = spec.path("paths").path("/api/v1/merchant/shop/resubmit").path("post");
        assertThat(resubmit.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(resubmit.path("parameters").isMissingNode()).isTrue();
        assertThat(resubmit.path("responses").has("409")).isTrue();
        assertThat(spec.path("components").path("schemas").path("MerchantResubmitRequest").path("properties")
                .path("expectedVersion").path("type").asString()).isEqualTo("string");
        JsonNode components = spec.path("components");
        assertThat(components.path("securitySchemes").path("bearerAuth").path("scheme").asString()).isEqualTo("bearer");
        for (String endpoint : List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/system/health")) {
            assertThat(spec.path("paths").path(endpoint).path(endpoint.endsWith("/health") ? "get" : "post")
                    .path("security").isMissingNode()).isTrue();
        }
        for (String method : List.of("get", "patch")) {
            assertThat(spec.path("paths").path("/api/v1/me").path(method).path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(spec.path("paths").path("/api/v1/me").path(method).path("parameters").isMissingNode()).isTrue();
        }
        assertThat(spec.path("paths").path("/api/v1/auth/register").path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/merchant-applications").path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/merchant/shop").path("get").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(components.path("schemas").path("MerchantApplicationRequest").path("properties")
                .path("categoryId").path("type").asString()).isEqualTo("string");
        assertThat(spec.path("paths").path("/api/v1/me").path("patch").path("responses").path("409")
                .path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/ApiError");
        JsonNode profile = components.path("schemas").path("ProfileUpdateRequest");
        assertThat(profile.path("properties").path("nickname").path("type").asString()).isEqualTo("string");
        assertThat(profile.path("properties").path("expectedVersion").path("type").asString()).isEqualTo("string");
        assertThat(profile.path("required").toString()).contains("expectedVersion").doesNotContain("nickname");
        assertThat(profile.path("additionalProperties").asBoolean()).isFalse();
        assertThat(profile.path("properties").path("expectedVersion").path("pattern").asString()).isEqualTo("^(0|[1-9][0-9]{0,18})$");
        assertThat(components.path("schemas").path("ApiResponseVoid").path("properties").path("data").path("nullable").asBoolean()).isTrue();
        assertThat(components.path("schemas").path("AccountView").path("properties").path("version").path("type").asString()).isEqualTo("string");
        assertThat(response.body()).doesNotContain("AuthPrincipal", "passwordHash", "password_hash", "JWT_SECRET", "DB_PASSWORD", "_test");
        assertThat(spec.path("servers").get(0).path("url").asString()).isEqualTo(baseUrl());
        validateReferences(spec, components.path("schemas"));
        // Only a public schema is exported; normalize the random test port for local Apifox import.
        spec.putArray("servers").addObject().put("url", "http://127.0.0.1:8080").put("description", "本机开发环境");
        Path output = Path.of("target", "openapi", "campuslife.openapi.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec), StandardCharsets.UTF_8);
    }

    @Test
    void swaggerPageConfigurationAndJavascriptAreReachableWithoutLoggingIn() throws Exception {
        assertThat(call("GET", "/swagger-ui.html", null, null).statusCode()).isIn(301, 302, 307, 308);
        assertThat(call("GET", "/swagger-ui/index.html", null, null).body()).contains("Swagger UI", "swagger-ui-bundle.js");
        for (String path : List.of("/swagger-ui/swagger-ui.css", "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js")) {
            assertThat(call("GET", path, null, null).statusCode()).as(path).isEqualTo(200);
        }
        var configResponse = call("GET", "/v3/api-docs/swagger-config", null, null);
        assertThat(configResponse.statusCode()).isEqualTo(200);
        JsonNode config = mapper.readTree(configResponse.body());
        assertThat(config.path("url").asString()).isEqualTo("/v3/api-docs");
        assertThat(config.path("persistAuthorization").asBoolean()).isFalse();
        assertThat(config.path("validatorUrl").asString()).isEqualTo("none");
        assertThat(call("GET", "/v3/api-docs", null, "invalid").statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/private/future", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void documentedExamplesSupportLoginProfileUpdateAndConflictAgainstRealMysql() throws Exception {
        JsonNode schemas = mapper.readTree(call("GET", "/v3/api-docs", null, null).body()).path("components").path("schemas");
        String username = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String password = schemas.path("RegisterRequest").path("properties").path("password").path("example").asString();
        long id = 0;
        try {
            var registration = call("POST", "/api/v1/auth/register", mapper.writeValueAsString(Map.of("username", username,
                    "password", password, "nickname", schemas.path("RegisterRequest").path("properties").path("nickname").path("example").asString())), null);
            assertThat(registration.statusCode()).isEqualTo(201);
            id = Long.parseLong(mapper.readTree(registration.body()).path("data").path("id").asString());
            var login = call("POST", "/api/v1/auth/login", mapper.writeValueAsString(Map.of("username", username, "password", password)), null);
            assertThat(login.statusCode()).isEqualTo(200);
            String token = mapper.readTree(login.body()).path("data").path("accessToken").asString();
            JsonNode before = mapper.readTree(call("GET", "/api/v1/me", null, token).body()).path("data");
            String nickname = schemas.path("ProfileUpdateRequest").path("properties").path("nickname").path("example").asString();
            String body = mapper.writeValueAsString(Map.of("nickname", nickname, "expectedVersion", before.path("version").asString()));
            var updated = call("PATCH", "/api/v1/me", body, token);
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(updated.body()).path("data").path("nickname").asString()).isEqualTo(nickname);
            var conflict = call("PATCH", "/api/v1/me", body, token);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(mapper.readTree(conflict.body()).path("code").asString()).isEqualTo("CONFIG_VERSION_CONFLICT");
        } finally {
            if (id != 0) {
                jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
                jdbc.update("DELETE FROM account WHERE id = ?", id);
            }
        }
    }

    private String baseUrl() { return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"); }

    private void validateReferences(JsonNode node, JsonNode schemas) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String reference = node.path("$ref").asString();
                assertThat(reference).startsWith("#/components/schemas/");
                assertThat(schemas.has(reference.substring("#/components/schemas/".length())))
                        .as("Missing schema for %s", reference).isTrue();
            }
            node.properties().forEach(entry -> validateReferences(entry.getValue(), schemas));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) { validateReferences(node.get(index), schemas); }
        }
    }
    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(java.time.Duration.ofSeconds(15));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
