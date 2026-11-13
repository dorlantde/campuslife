package com.campuslife.file.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.storage.FileStorage;
import com.campuslife.file.storage.FileLocation;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class FileDownloadService {
    private static final Logger log=LoggerFactory.getLogger(FileDownloadService.class);
    private final FileStorage storage;
    private final FileTransactions transactions;

    public FileDownloadService(FileStorage storage,FileTransactions transactions) {
        this.storage=storage;this.transactions=transactions;
    }

    public void download(AuthPrincipal principal,String fileId,HttpServletResponse response)throws IOException {
        FileRules.downloadRole(principal.account().role());
        long id=FileRules.id(fileId);
        var located=authorize(principal,id);
        var guard=storage.lock(located.getStorageKey());
        guard.lock();
        try {
            var row=authorize(principal,id);
            try { FileLocation.requireSame(located,row); } catch(IOException|IllegalArgumentException ex) { throw FileRules.unavailable(); }
            try(var input=storage.open(row)) {
                byte[] buffer=new byte[8192];
                int n=input.read(buffer); // Detect initial read failures before preparing binary headers.
                if(n<0){throw new IOException("Empty object");}
                response.setStatus(200);
                response.setCharacterEncoding((String)null); // The application's UTF-8 filter is for JSON, not PDF bytes.
                response.setContentType("application/pdf");
                response.setContentLengthLong(row.getByteSize());
                response.setHeader("Content-Disposition",disposition(row.getOriginalName()));
                response.setHeader("Cache-Control","no-store");
                response.setHeader("X-Content-Type-Options","nosniff");
                var output=response.getOutputStream();
                long bytes=0;
                do {
                    bytes+=n;
                    if(bytes>row.getByteSize()){throw new IOException("Object grew during transfer");}
                    output.write(buffer,0,n);
                }while((n=input.read(buffer))!=-1);
                if(bytes!=row.getByteSize()){throw new IOException("Object shortened during transfer");}
                response.flushBuffer();
            }catch(IOException ex) {
                if(!response.isCommitted()) {
                    response.resetBuffer();
                    response.setContentType(null);
                    response.setHeader("Content-Length",null);
                    response.setHeader("Content-Disposition",null);
                    throw FileRules.unavailable();
                }
                // The exception handler must not append JSON; keep disk paths out of this log.
                log.warn("File transfer interrupted id={} traceId={}",id,MDC.get("traceId"));
                // Let the servlet container abort the incomplete HTTP message instead of keeping it alive.
                throw new IOException("PDF transfer interrupted");
            }
        }finally{guard.unlock();}
    }
    private FileAssetEntity authorize(AuthPrincipal principal,long id) {
        try{return transactions.download(principal,id);}
        catch(org.springframework.dao.TransientDataAccessException ex){
            throw FileRules.error(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","数据库暂不可用，请稍后重试下载");
        }
    }

    private static String disposition(String name) {
        var encoded=new StringBuilder();
        for(byte b:name.getBytes(StandardCharsets.UTF_8)) {
            int c=b&255;
            if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='.'||c=='-'||c=='_'){encoded.append((char)c);}
            else{encoded.append('%').append(Character.toUpperCase(Character.forDigit(c>>>4,16))).append(Character.toUpperCase(Character.forDigit(c&15,16)));}
        }
        return "attachment; filename=\"campuslife-file.pdf\"; filename*=UTF-8''"+encoded;
    }
}
