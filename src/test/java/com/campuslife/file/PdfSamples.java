package com.campuslife.file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

public final class PdfSamples {
    private PdfSamples() {}
    public static byte[] pdf(int pages)throws IOException{return encrypted(pages,null);}
    public static byte[] encrypted(int pages,String password)throws IOException {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++){doc.addPage(new PDPage());}
            if(password!=null){var policy=new StandardProtectionPolicy("test-owner-password",password,new AccessPermission());policy.setEncryptionKeyLength(128);doc.protect(policy);}
            doc.save(out,CompressParameters.NO_COMPRESSION);return out.toByteArray();
        }
    }
    public static byte[] sized(int size)throws IOException {
        byte[] base=pdf(1);String text=new String(base,StandardCharsets.ISO_8859_1);int trailer=text.lastIndexOf("trailer");
        if(trailer<0||size<base.length){throw new IllegalArgumentException();}
        byte[] result=new byte[size];System.arraycopy(base,0,result,0,trailer);java.util.Arrays.fill(result,trailer,trailer+size-base.length,(byte)' ');System.arraycopy(base,trailer,result,trailer+size-base.length,base.length-trailer);return result;
    }
    public static void main(String[] args)throws IOException {
        java.nio.file.Path path=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.write(path,pdf(2));
    }
}
