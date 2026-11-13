package com.campuslife.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="本人可用PDF的服务端元数据；不含路径、存储key或公开下载地址")
public record FileView(String id,String originalName,
        @Schema(minimum="1",maximum="20971520") long byteSize,
        @Schema(allowableValues="application/pdf") String mediaType,
        @Schema(minimum="1",maximum="500") int pageCount,
        @Schema(allowableValues="AVAILABLE") String status,
        @Schema(nullable=true,description="UTC；未引用上传24小时，任一打印订单未终结时null，全部终结后7天") Instant expiresAt) {}
