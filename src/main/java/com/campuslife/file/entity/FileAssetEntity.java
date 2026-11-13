package com.campuslife.file.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("file_asset")
public class FileAssetEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long ownerId;
    private Long byteSize;
    private Long version;
    private Integer pdfPageCount;
    private String storageKind;
    private String storageId;
    private String storageKey;
    private String originalName;
    private String sha256;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id=value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { this.ownerId=value; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long value) { this.byteSize=value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version=value; }
    public Integer getPdfPageCount() { return pdfPageCount; }
    public void setPdfPageCount(Integer value) { this.pdfPageCount=value; }
    public String getStorageKind() { return storageKind; }
    public void setStorageKind(String value) { this.storageKind=value; }
    public String getStorageId() { return storageId; }
    public void setStorageId(String value) { this.storageId=value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { this.storageKey=value; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String value) { this.originalName=value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { this.sha256=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status=value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { this.expiresAt=value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt=value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt=value; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime value) { this.deletedAt=value; }
}
