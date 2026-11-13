package com.campuslife.file.service;

import com.campuslife.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.time.*;
import java.util.Locale;

public final class FileRules {
    public static final long MAX_BYTES=20L*1024*1024;
    private FileRules() {}
    public static void student(String role) {
        if (!"STUDENT".equals(role)) { throw error(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅学生可访问本人上传文件"); }
    }
    public static void downloadRole(String role) {
        if(!"STUDENT".equals(role)&&!"MERCHANT".equals(role)){throw error(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅学生本人或具有本店打印任务授权的商家可下载");}
    }
    public static long id(String value) {
        try {
            if (value==null || !value.matches("[1-9][0-9]{0,18}")) { throw new NumberFormatException(); }
            return Long.parseLong(value);
        } catch (NumberFormatException ex) { throw invalid(); }
    }
    public static String name(MultipartFile file) {
        if (file==null || file.isEmpty()) { throw invalid(); }
        if (file.getSize()>MAX_BYTES) { throw tooLarge(); }
        String name=file.getOriginalFilename();if (name==null) { throw invalid(); }
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if (name.isEmpty() || name.codePointCount(0,name.length())>200 || name.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) { throw type(); }
        String declared=file.getContentType();
        if (declared!=null && !declared.isBlank()) {
            try {
                var media=MediaType.parseMediaType(declared);
                if (!MediaType.APPLICATION_PDF.isCompatibleWith(media) && !MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(media)) { throw type(); }
                if (media.isWildcardType() || media.isWildcardSubtype()) { throw type(); }
            } catch (IllegalArgumentException ex) { throw type(); }
        }
        return name;
    }
    public static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
    public static Instant instant(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC); }
    public static BusinessException error(HttpStatus status,String code,String message) { return new BusinessException(status,code,message); }
    public static BusinessException invalid() { return error(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","须上传一个合法命名的非空PDF，不接收额外字段"); }
    public static BusinessException tooLarge() { return error(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","单个PDF最多20 MiB"); }
    public static BusinessException type() { return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"FILE_TYPE_UNSUPPORTED","仅接收PDF文件"); }
    public static BusinessException unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE,"FILE_STORAGE_UNAVAILABLE","文件暂时无法保存或清理，请稍后重试"); }
    public static BusinessException missing() { return error(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","可见文件不存在"); }
}
