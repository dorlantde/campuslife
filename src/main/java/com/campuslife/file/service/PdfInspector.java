package com.campuslife.file.service;

import java.io.*;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class PdfInspector {
    public int pages(Path path)throws IOException {
        try(var input=Files.newInputStream(path)) {
            if(!java.util.Arrays.equals(input.readNBytes(5),new byte[]{'%','P','D','F','-'})){throw invalid();}
        }
        try(var document=Loader.loadPDF(path.toFile(),()->new ScratchFile(MemoryUsageSetting.setupTempFileOnly().setTempDir(path.getParent().toFile())))) {
            if(document.isEncrypted()){throw encrypted();}
            int count=document.getNumberOfPages();if(count<1){throw invalid();}
            if(count>500){throw FileRules.error(HttpStatus.UNPROCESSABLE_ENTITY,"PDF_PAGE_LIMIT_EXCEEDED","PDF最多500页");}
            int actual=0;for(var page:document.getPages()){page.getMediaBox();if(++actual>500){throw FileRules.error(HttpStatus.UNPROCESSABLE_ENTITY,"PDF_PAGE_LIMIT_EXCEEDED","PDF最多500页");}}
            if(actual!=count){throw invalid();}return count;
        }catch(InvalidPasswordException ex){throw encrypted();}
        catch(IOException|IllegalArgumentException ex){throw invalid();}
    }
    private static com.campuslife.common.exception.BusinessException encrypted(){return FileRules.error(HttpStatus.UNPROCESSABLE_ENTITY,"PDF_ENCRYPTED","请先导出无加密PDF后上传");}
    private static com.campuslife.common.exception.BusinessException invalid(){return FileRules.error(HttpStatus.UNPROCESSABLE_ENTITY,"PDF_INVALID","PDF格式或页树无法解析");}
}
