package com.campuslife.file.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.vo.FileView;
import com.campuslife.file.storage.FileLocation;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.campuslife.file.service.FileRules.*;

@Service
@Profile("mysql")
public class FileTransactions {
    private final FileAssetMapper files;private final AccountMapper accounts;private final AuthSessionMapper sessions;private final Clock clock;
    private final com.campuslife.file.mapper.FileReferenceMapper references;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FileTransactions.class);
    public FileTransactions(FileAssetMapper files,AccountMapper accounts,AuthSessionMapper sessions,Clock clock,com.campuslife.file.mapper.FileReferenceMapper references){this.files=files;this.accounts=accounts;this.sessions=sessions;this.clock=clock;this.references=references;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FileAssetEntity reserve(AuthPrincipal principal,FileLocation location,String name,long bytes,String hash,int pages) {
        var session=access(principal);var now=now();var row=new FileAssetEntity();
        row.setOwnerId(principal.accountId());row.setStorageKind(location.kind());row.setStorageId(location.namespace());row.setStorageKey(location.key());row.setOriginalName(name);row.setByteSize(bytes);row.setSha256(hash);row.setPdfPageCount(pages);row.setStatus("UPLOADING");row.setVersion(0L);row.setCreatedAt(utc(now));row.setUpdatedAt(utc(now));row.setExpiresAt(utc(now.plusSeconds(1800)));
        one(files.insert(row));valid(session);return row;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FileView publish(AuthPrincipal principal,FileAssetEntity reserved) {
        long id=reserved.getId();var session=access(principal);var row=files.lock(id);var now=now();
        if(row==null||row.getOwnerId()!=principal.accountId()||!"UPLOADING".equals(row.getStatus())
                ||!row.getVersion().equals(reserved.getVersion())||!now.isBefore(instant(row.getExpiresAt()))){throw unavailable();}
        try { FileLocation.requireSame(reserved,row); } catch(java.io.IOException|IllegalArgumentException ex) { throw unavailable(); }
        version(row);one(files.publish(id,row.getVersion(),utc(now.plusSeconds(86400)),utc(now)));valid(session);return view(files.lock(id));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FileView metadata(AuthPrincipal principal,long id) {
        student(principal.account().role());var row=files.selectById(id);
        if(row==null||row.getOwnerId()!=principal.accountId()||"UPLOADING".equals(row.getStatus())){throw missing();}
        if(!"AVAILABLE".equals(row.getStatus())||(row.getExpiresAt()!=null&&!clock.instant().isBefore(instant(row.getExpiresAt())))){throw error(HttpStatus.GONE,"FILE_EXPIRED","文件已过期或进入删除流程");}
        return view(row);
    }
    /** A new snapshot after waiting for the local file guard; no database locks cover IO. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FileAssetEntity download(AuthPrincipal principal,long id) {
        downloadRole(principal.account().role());
        var account=sessions.find(principal.accountId(),principal.jti());
        var now=clock.instant();
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(!now.isBefore(instant(account.expiresAt()))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
        if(account.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!"ACTIVE".equals(account.status())){throw error(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        downloadRole(account.role());
        if(!account.role().equals(principal.account().role())){throw error(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","登录角色已变化，请重新发起请求");}
        if("MERCHANT".equals(account.role())){return merchantDownload(principal.accountId(),id);}
        var row=files.selectById(id);
        if(row==null||row.getOwnerId()!=principal.accountId()||"UPLOADING".equals(row.getStatus())){throw missing();}
        if(!"AVAILABLE".equals(row.getStatus())||(row.getExpiresAt()!=null&&!now.isBefore(instant(row.getExpiresAt())))){throw error(HttpStatus.GONE,"FILE_EXPIRED","文件已过期或进入删除流程");}
        return row;
    }
    private FileAssetEntity merchantDownload(long account,long id) {
        var owner=references.downloadOwner(account);if(owner==null){throw missing();}
        if(!"ACTIVE".equals(owner.memberStatus())){throw error(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","本人店主成员未激活或已停用");}
        if(!references.merchantDownloadAllowed(owner.shopId(),id)){throw missing();}
        // The grant and metadata belong to the same RR snapshot, including the order student's ownership.
        var row=files.selectById(id);
        if(row==null||!"AVAILABLE".equals(row.getStatus())||row.getExpiresAt()!=null){throw unavailable();}
        return row;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FileAssetEntity markDeleting(long id,String kind,String namespace,boolean failedUpload) {
        var row=files.lock(id);var now=now();
        if(row==null||!kind.equals(row.getStorageKind())||!namespace.equals(row.getStorageId())||"DELETED".equals(row.getStatus())){return null;}
        if(failedUpload){if(!"UPLOADING".equals(row.getStatus())){return null;}}
        else if(!"DELETING".equals(row.getStatus())&&(row.getExpiresAt()==null||now.isBefore(instant(row.getExpiresAt())))){return null;}
        if(references.unfinished(id)>0){log.warn("File cleanup skipped: unfinished print references for file {}",id);return null;}
        version(row);one(files.deleting(id,row.getVersion(),utc(now)));return files.lock(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void finishDeleting(FileAssetEntity deleted) {
        long id=deleted.getId();var row=files.lock(id);
        if(row==null||!FileLocation.of(deleted).equals(FileLocation.of(row))||!"DELETING".equals(row.getStatus())||!row.getVersion().equals(deleted.getVersion())){return;}
        version(row);one(files.deleted(id,row.getVersion(),utc(now())));
    }
    /** Fresh persistent facts before a tombstone delete. No locks or transaction extend into storage IO. */
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public FileAssetEntity tombstone(long id,String namespace,long expectedVersion) {
        var row=files.selectById(id);
        if(row==null||!"S3".equals(row.getStorageKind())||!namespace.equals(row.getStorageId())
                ||!"DELETED".equals(row.getStatus())||row.getVersion()!=expectedVersion||references.unfinished(id)>0){return null;}
        return row;
    }
    private AuthSessionState access(AuthPrincipal principal) {
        student(principal.account().role());var account=accounts.lockById(principal.accountId());
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        if(!"ACTIVE".equals(account.getStatus())){throw error(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        student(account.getRole());var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);return session;
    }
    private void valid(AuthSessionState row) {
        if(row==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(row.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!clock.instant().isBefore(instant(row.expiresAt()))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static void version(FileAssetEntity row){if(row.getVersion()==Long.MAX_VALUE){throw unavailable();}}
    private static void one(int n){if(n!=1){throw new IllegalStateException("File conditional write failed");}}
    private static FileView view(FileAssetEntity row){return new FileView(row.getId().toString(),row.getOriginalName(),row.getByteSize(),"application/pdf",row.getPdfPageCount(),row.getStatus(),instant(row.getExpiresAt()));}
}
