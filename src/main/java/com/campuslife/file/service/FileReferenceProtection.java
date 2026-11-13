package com.campuslife.file.service;

import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.*;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class FileReferenceProtection {
    private final FileAssetMapper files;private final FileReferenceMapper refs;
    public FileReferenceProtection(FileAssetMapper files,FileReferenceMapper refs){this.files=files;this.refs=refs;}
    /** Called only after file X and the order transition in the same RC transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void refresh(FileAssetEntity locked,LocalDateTime now){
        LocalDateTime expiry=null;
        if(refs.unfinished(locked.getId())==0){
            var terminal=refs.lastTerminal(locked.getId());
            if(terminal==null){throw new IllegalStateException("Missing print terminal fact");}
            expiry=terminal.plusDays(7);
        }
        if(Objects.equals(expiry,locked.getExpiresAt())){return;}
        if(!"AVAILABLE".equals(locked.getStatus())||locked.getVersion()==Long.MAX_VALUE){throw FileRules.unavailable();}
        if(files.expiry(locked.getId(),locked.getVersion(),expiry,now)!=1){throw new IllegalStateException("File retention write failed");}
    }
}
