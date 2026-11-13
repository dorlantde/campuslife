package com.campuslife.file.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.storage.LocalFileStorage;
import com.campuslife.file.storage.FileStorage;
import com.campuslife.file.vo.FileView;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("mysql")
public class FileUploadService {
    private static final Logger log=LoggerFactory.getLogger(FileUploadService.class);
    private final LocalFileStorage storage;private final FileStorage content;private final PdfInspector inspector;private final FileTransactions transactions;private final FileCleanup cleanup;
    private final Semaphore processing=new Semaphore(2);
    public FileUploadService(LocalFileStorage storage,FileStorage content,PdfInspector inspector,FileTransactions transactions,FileCleanup cleanup){this.storage=storage;this.content=content;this.inspector=inspector;this.transactions=transactions;this.cleanup=cleanup;}
    public FileView upload(AuthPrincipal principal,MultipartFile file) {
        FileRules.student(principal.account().role());String name=FileRules.name(file);
        if(!processing.tryAcquire()){throw FileRules.error(HttpStatus.TOO_MANY_REQUESTS,"FILE_PROCESSING_BUSY","已有两个PDF正在处理，请稍后重试");}
        LocalFileStorage.Received received=null;
        try {
            try(var input=file.getInputStream()){received=storage.receive(input);}
            int pages=inspector.pages(received.path());String key=UUID.randomUUID().toString().replace("-","")+".pdf";
            var guard=content.lock(key);guard.lock();FileAssetEntity row=null;
            try {
                row=transactions.reserve(principal,content.uploadLocation(key),name,received.bytes(),received.sha256(),pages);
                content.write(row,received);return transactions.publish(principal,row);
            }catch(IOException|RuntimeException ex){
                if(row!=null){try{cleanup.remove(row.getId(),true);}catch(RuntimeException compensation){log.warn("Upload compensation will retry id={}",row.getId());}}
                if(ex instanceof IOException){throw FileRules.unavailable();}throw (RuntimeException)ex;
            }finally{guard.unlock();}
        }catch(IOException ex){throw FileRules.unavailable();}
        finally{
            if(received!=null){try{storage.releaseTemporary(received.path());}catch(IOException|RuntimeException ex){log.warn("Upload temporary cleanup will retry");}}
            processing.release();
        }
    }
    public FileView metadata(AuthPrincipal principal,String id){FileRules.student(principal.account().role());return transactions.metadata(principal,FileRules.id(id));}
}
