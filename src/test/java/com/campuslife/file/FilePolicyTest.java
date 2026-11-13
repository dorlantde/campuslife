package com.campuslife.file;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.LocalFileStorage;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class FilePolicyTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"0","01","-1","+1","1.0"," 1","9223372036854775808","99999999999999999999"})
    void fileIdsRemainCanonical(String value){assertThatThrownBy(()->FileRules.id(value)).isInstanceOf(BusinessException.class);}
    @ParameterizedTest @ValueSource(strings={"demo.pdf","demo.PDF","C:\\fakepath\\demo.pdf","../../demo.pdf"})
    void namesAreDisplayBasenamesOnly(String value){assertThat(FileRules.name(new MockMultipartFile("file",value,"application/pdf",new byte[]{1}))).isEqualTo(value.endsWith("PDF")?"demo.PDF":"demo.pdf");}
    @ParameterizedTest @ValueSource(strings={"bad\r.pdf","bad\n.pdf","bad\u0000.pdf","","bad.txt"})
    void invalidNamesAreRejected(String value){assertThatThrownBy(()->FileRules.name(new MockMultipartFile("file",value,"application/pdf",new byte[]{1}))).isInstanceOf(BusinessException.class);}
    @ParameterizedTest @ValueSource(strings={"application/pdf","application/pdf;charset=UTF-8","application/octet-stream",""})
    void supportedMimeStillRequiresParsing(String mime){assertThat(FileRules.name(new MockMultipartFile("file","demo.pdf",mime,new byte[]{1}))).isEqualTo("demo.pdf");}
    @ParameterizedTest @ValueSource(strings={"image/png","text/plain","*/*","not a media type"})
    void unsupportedMimeIsRejected(String mime){assertThatThrownBy(()->FileRules.name(new MockMultipartFile("file","demo.pdf",mime,new byte[]{1}))).isInstanceOf(BusinessException.class);}
    @Test void nameLengthCountsCodePoints(){String good="学".repeat(196)+".pdf";assertThat(FileRules.name(new MockMultipartFile("file",good,null,new byte[]{1}))).isEqualTo(good);assertThatThrownBy(()->FileRules.name(new MockMultipartFile("file","学".repeat(197)+".pdf",null,new byte[]{1}))).isInstanceOf(BusinessException.class);}
    @ParameterizedTest @ValueSource(ints={1,500})
    void actualPdfBoundariesParse(int pages)throws Exception{Path file=directory.resolve("test.pdf");Files.write(file,PdfSamples.pdf(pages));assertThat(new PdfInspector().pages(file)).isEqualTo(pages);}
    @ParameterizedTest @CsvSource({"0,PDF_INVALID","501,PDF_PAGE_LIMIT_EXCEEDED"})
    void actualPageLimitsReject(int pages,String code)throws Exception{Path file=directory.resolve("test.pdf");Files.write(file,PdfSamples.pdf(pages));var ex=catchThrowable(()->new PdfInspector().pages(file));assertThat(ex).isInstanceOf(BusinessException.class);assertThat(((BusinessException)ex).code()).isEqualTo(code);}
    @ParameterizedTest @ValueSource(strings={"","secret"})
    void encryptedIncludesEmptyPassword(String password)throws Exception{Path file=directory.resolve("test.pdf");Files.write(file,PdfSamples.encrypted(1,password));var ex=catchThrowable(()->new PdfInspector().pages(file));assertThat(((BusinessException)ex).code()).isEqualTo("PDF_ENCRYPTED");}
    @ParameterizedTest @ValueSource(strings={"not a pdf","%PDF-1.7\nnot a valid document"})
    void magicAloneIsNotEnough(String text)throws Exception{Path file=directory.resolve("test.pdf");Files.writeString(file,text);assertThatThrownBy(()->new PdfInspector().pages(file)).isInstanceOf(BusinessException.class);}
    @Test void namespaceAndActiveTemporaryRecoveryAreBounded()throws Exception{
        var clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);var storage=new LocalFileStorage(directory.resolve("store").toString(),clock);var id=storage.storageId();assertThat(new LocalFileStorage(storage.root().toString(),clock).storageId()).isEqualTo(id);
        var received=storage.receive(new java.io.ByteArrayInputStream(PdfSamples.pdf(1)));Files.setLastModifiedTime(received.path(),FileTime.from(clock.instant().minusSeconds(3600)));storage.cleanTemporary();assertThat(received.path()).exists();storage.releaseTemporary(received.path());assertThat(received.path()).doesNotExist();
        var orphan=storage.root().resolve("temporary/"+"a".repeat(32)+".part");Files.writeString(orphan,"stale");Files.setLastModifiedTime(orphan,FileTime.from(clock.instant().minusSeconds(1800)));var unknown=orphan.resolveSibling("keep.txt");Files.writeString(unknown,"not owned");new LocalFileStorage(storage.root().toString(),clock).cleanTemporary();assertThat(orphan).doesNotExist();assertThat(unknown).exists();
        assertThatThrownBy(()->storage.delete("wrong", "a".repeat(32)+".pdf")).isInstanceOf(java.io.IOException.class);assertThatThrownBy(()->storage.delete(id,"../keep.txt")).isInstanceOf(java.io.IOException.class);
    }
    @Test void realFilesystemLinkCannotEscapeRoot()throws Exception {
        Path outside=directory.resolve("outside");Files.createDirectories(outside);Path keep=outside.resolve("keep.txt");Files.writeString(keep,"protected");Path link=directory.resolve("linked");
        if(System.getProperty("os.name").startsWith("Windows")){
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
        }else{Files.createSymbolicLink(link,outside);}
        try{assertThatThrownBy(()->new LocalFileStorage(link.resolve("files").toString(),Clock.systemUTC()).storageId()).isInstanceOf(BusinessException.class);assertThat(keep).exists();}finally{Files.delete(link);}
    }
    @Test void streamingLimitDoesNotTrustDeclaredFileLength()throws Exception {
        var storage=new LocalFileStorage(directory.resolve("store").toString(),Clock.systemUTC());storage.storageId();
        var input=new java.io.InputStream(){long remaining=FileRules.MAX_BYTES+1;public int read(){return remaining-->0?'x':-1;}public int read(byte[] b,int offset,int length){if(remaining<=0){return -1;}int n=(int)Math.min(remaining,length);java.util.Arrays.fill(b,offset,offset+n,(byte)'x');remaining-=n;return n;}};
        var ex=catchThrowable(()->storage.receive(input));assertThat(((BusinessException)ex).code()).isEqualTo("FILE_TOO_LARGE");try(var paths=Files.list(storage.root().resolve("temporary"))){assertThat(paths.count()).isZero();}
    }
    @Test void downloadRejectsRealLinkedObjectDirectory()throws Exception {
        var storage=new LocalFileStorage(directory.resolve("store").toString(),Clock.systemUTC());var namespace=storage.storageId();
        Path objects=storage.root().resolve("objects"),saved=storage.root().resolve("original-objects"),outside=directory.resolve("outside");
        Files.createDirectory(outside);String key="a".repeat(32)+".pdf";Path protectedFile=outside.resolve(key);Files.write(protectedFile,new byte[]{1});Files.move(objects,saved);
        if(System.getProperty("os.name").startsWith("Windows")) {
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",objects.toString(),outside.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
        }else{Files.createSymbolicLink(objects,outside);}
        try {
            assertThatThrownBy(()->storage.open(namespace,key,1)).isInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(protectedFile)).containsExactly((byte)1);
        }finally{Files.delete(objects);Files.move(saved,objects);}
    }
}
