package com.campuslife.common.exception;

import com.campuslife.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .headers(headers -> { if (exception.status() == HttpStatus.UNAUTHORIZED) { headers.set("WWW-Authenticate", "Bearer"); } })
                .contentType(JSON_UTF8)
                .body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().contentType(JSON_UTF8)
                .body(ApiResponse.failure("VALIDATION_FAILED", "请求参数不符合要求"));
    }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDatabaseContention(org.springframework.dao.TransientDataAccessException exception) {
        log.warn("Database operation rolled back after transient contention", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(JSON_UTF8)
                .body(ApiResponse.failure("SERVICE_TEMPORARILY_UNAVAILABLE", "数据库竞争已回滚，请稍后重试；创建订单请保留原请求键"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedMultipart(org.springframework.web.multipart.MultipartException exception) {
        return ResponseEntity.badRequest().contentType(JSON_UTF8)
                .body(ApiResponse.failure("VALIDATION_FAILED", "文件上传表单格式错误"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code;
        String message;
        switch (status.value()) {
            case 400 -> {
                code = "VALIDATION_FAILED";
                message = "请求参数或 JSON 格式不符合要求";
            }
            case 404 -> {
                code = "RESOURCE_NOT_FOUND";
                message = "资源不存在";
            }
            case 405 -> {
                code = "METHOD_NOT_ALLOWED";
                message = "该资源不支持此请求方法";
            }
            case 406 -> {
                code = "NOT_ACCEPTABLE";
                message = "不支持请求指定的响应格式";
            }
            case 415 -> {
                code = "UNSUPPORTED_MEDIA_TYPE";
                message = "不支持此请求内容类型";
            }
            case 413 -> {
                code = "FILE_TOO_LARGE";
                message = "PDF最多20 MiB，上传表单总大小最多22 MiB";
            }
            default -> {
                code = status.is5xxServerError() ? "INTERNAL_ERROR" : "HTTP_ERROR";
                message = status.is5xxServerError() ? "服务暂时无法处理请求" : "请求无法处理";
            }
        }
        if (status.is5xxServerError()) {
            log.error("Unexpected MVC failure", exception);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(JSON_UTF8);
        return new ResponseEntity<>(ApiResponse.failure(code, message), responseHeaders, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception,jakarta.servlet.http.HttpServletResponse response)throws Exception {
        // An incomplete binary response must reach the container so it closes the connection.
        // Returning JSON (or swallowing the failure) here could corrupt the PDF or leave clients waiting.
        if(response.isCommitted()){throw exception;}
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(JSON_UTF8)
                .body(ApiResponse.failure("INTERNAL_ERROR", "服务暂时无法处理请求"));
    }
}
