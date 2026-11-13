package com.campuslife.file.controller;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import com.campuslife.file.service.*;
import com.campuslife.file.vo.FileView;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@Profile("mysql")
@Tag(name="04 文件与打印报价",description="学生私有PDF上传、本人元数据/下载与打印报价；商家按本店有效打印任务受控下载")
@SecurityRequirement(name="bearerAuth")
public class FileController {
    private final FileUploadService service;
    private final FileDownloadService downloads;
    public FileController(FileUploadService service,FileDownloadService downloads){this.service=service;this.downloads=downloads;}
    @PostMapping(value="/api/v1/files",consumes="multipart/form-data",produces="application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary="上传私有打印PDF",description="仅正常STUDENT。单个file，1～20 MiB、1～500页，服务器校验签名/可解析页树并计算实际大小、页数与SHA-256；所有加密PDF拒绝。最多两个上传处理，忙时429。禁止额外业务字段；文件名取末段1～200码点、.pdf后缀，无控制字符。允许PDF/octet-stream/未声明部件MIME；不信任客户端页数。发布前复核账号/会话，成功201 AVAILABLE，未关联订单24小时到期；重复上传创建独立ID。不返回存储位置/下载URL；关联打印订单后按全部引用保护文件期限。")
    public ResponseEntity<ApiResponse<FileView>> upload(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file,@Parameter(hidden=true) MultipartHttpServletRequest request)throws java.io.IOException,jakarta.servlet.ServletException {
        FileRules.student(principal.account().role());
        if(!request.getParameterMap().isEmpty()||request.getMultiFileMap().size()!=1||request.getFiles("file").size()!=1||request.getParts().size()!=1){throw FileRules.invalid();}
        return ResponseEntity.status(201).body(ApiResponse.success(service.upload(principal,file)));
    }
    @GetMapping(value="/api/v1/files/{fileId}",produces="application/json")
    @Operation(summary="读取本人PDF元数据",description="仅正常STUDENT本人已发布文件，先归属后到期：他人/不存在/内部未发布统一404；本人到期（等于即过期）或删除中/已删除410 FILE_EXPIRED，尚未物理清理也不可使用。规范正BIGINT字符串ID，成功仅AVAILABLE；GET不续期、不解析/清理文件，不暴露路径/hash/下载地址。商家/ADMIN无本元数据入口权限；独立content接口支持本人学生及有合格本店打印任务的商家，expiresAt=null表示关联订单保护。")
    public ApiResponse<FileView> metadata(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String fileId){return ApiResponse.success(service.metadata(principal,fileId));}
    @GetMapping("/api/v1/files/{fileId}/content")
    @Operation(summary="下载受控PDF原文件",description="正常STUDENT本人沿用归属/有效期；正常MERCHANT及ACTIVE OWNER须本店至少一笔关联PRINT为合法ACCEPTED/QUEUED或IN_SERVICE/PRINTING、READY_FOR_PICKUP组合，均PAID、acceptedAt非空、completedAt/pickedUpAt空；已开始须startedAt，待取件还须readyAt，文件属于订单学生。暂停/停业仍可处理已接任务，不受原30分钟接单期限或资金开关限制；无合格关联404，7天文件保留不授予商家权限，打印中和待取件仍可按有效任务下载。等待文件互斥后新RR复核身份/会话/成员/关联及文件；获准传输允许完成，期间取消退款不等待下载，下次请求重新判断，清理跳过活动传输。学生本人到期/删除中410，他人/不存在/未发布404；商家授权后的保护元数据异常503。PDF附件不包装JSON、不续期/写事件，完整200，无Range断点续传或条件缓存。写流前错误为JSON，已提交后中断不追加JSON。ADMIN无默认内容权限。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="原PDF二进制附件；安全UTF-8附件名，Cache-Control: no-store",
        content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/pdf",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
    public void content(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,@PathVariable String fileId,
            @Parameter(hidden=true) jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{downloads.download(principal,fileId,response);}
}
