package com.campuslife;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback fault proxy. Original signed Host/headers/body are forwarded, never logged or persisted. */
final class S3FaultProxy implements AutoCloseable {
    enum Fault { NONE, HOLD_PUT, DROP_PUT_REPLY, TRUNCATE_GET, CORRUPT_GET }
    final HttpServer server;
    final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    final URI backend;
    volatile Fault fault=Fault.NONE;
    final CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicInteger heldRequests=new AtomicInteger(),forwardedPuts=new AtomicInteger(),deletes=new AtomicInteger();
    final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
    S3FaultProxy(String endpoint)throws IOException {
        backend=URI.create(endpoint);
        if(!"127.0.0.1".equals(backend.getHost())){throw new IllegalArgumentException("Loopback backend required");}
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setExecutor(workers);server.createContext("/",this::handle);server.start();
    }
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void handle(HttpExchange exchange)throws IOException {
        String method=exchange.getRequestMethod();boolean object=exchange.getRequestURI().getPath().matches("/[a-z0-9-]+/[a-f0-9-]{36}/objects/[a-f0-9]{32}[.]pdf");
        Fault selected=object?fault:Fault.NONE;
        try {
            byte[] body=exchange.getRequestBody().readNBytes(22*1024*1024);
            if(body.length>=22*1024*1024){throw new IOException("Owned fixture body bound");}
            if(selected==Fault.HOLD_PUT&&method.equals("PUT")){heldRequests.incrementAndGet();held.countDown();if(!release.await(90,TimeUnit.SECONDS)){throw new IOException("Held PUT not released");}}
            var response=forward(exchange,body);
            if(object&&method.equals("PUT")&&response.status/100==2){forwardedPuts.incrementAndGet();}
            if(object&&method.equals("DELETE")&&response.status/100==2){deletes.incrementAndGet();}
            events.add(Map.of("method",method,"object",object,"fault",selected.name(),"status",response.status,"at",java.time.Instant.now().toString()));
            if(selected==Fault.DROP_PUT_REPLY&&method.equals("PUT")){exchange.close();return;}
            byte[] result=response.body;
            for(var h:response.headers.entrySet()) {
                if(Set.of("content-length","transfer-encoding","connection").contains(h.getKey().toLowerCase(Locale.ROOT))){continue;}
                if((selected==Fault.CORRUPT_GET||selected==Fault.TRUNCATE_GET)&&h.getKey().toLowerCase(Locale.ROOT).startsWith("x-amz-checksum")){continue;}
                exchange.getResponseHeaders().put(h.getKey(),h.getValue());
            }
            if(selected==Fault.CORRUPT_GET&&method.equals("GET")&&result.length>0){result=result.clone();result[0]^=1;}
            exchange.sendResponseHeaders(response.status,result.length==0?-1:result.length);
            if(selected==Fault.TRUNCATE_GET&&method.equals("GET")&&result.length>32768){exchange.getResponseBody().write(result,0,16384);}
            else{exchange.getResponseBody().write(result);}
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}
        finally{try{exchange.close();}catch(RuntimeException ignored){}}
    }
    record Response(int status,Map<String,List<String>> headers,byte[] body){}
    Response forward(HttpExchange request,byte[] body)throws IOException {
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress(backend.getHost(),backend.getPort()),2000);socket.setSoTimeout(10000);
            var output=socket.getOutputStream();
            output.write((request.getRequestMethod()+" "+request.getRequestURI().toASCIIString()+" HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for(var h:request.getRequestHeaders().entrySet()) {
                if(h.getKey().equalsIgnoreCase("Connection")||h.getKey().equalsIgnoreCase("Transfer-Encoding")||h.getKey().equalsIgnoreCase("Content-Length")){continue;}
                for(String value:h.getValue()){output.write((h.getKey()+": "+value+"\r\n").getBytes(StandardCharsets.ISO_8859_1));}
            }
            output.write(("Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));output.write(body);output.flush();
            var input=new BufferedInputStream(socket.getInputStream());int status;Map<String,List<String>> headers;
            do {
                String first=line(input);if(first.isEmpty()){throw new EOFException("Backend response absent");}status=Integer.parseInt(first.split(" ")[1]);headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for(String h;(h=line(input)).length()>0;){int colon=h.indexOf(':');if(colon<1){throw new IOException("Invalid response header");}headers.computeIfAbsent(h.substring(0,colon),k->new ArrayList<>()).add(h.substring(colon+1).strip());}
            }while(status==100);
            byte[] data;
            if(headers.containsKey("Transfer-Encoding")&&headers.get("Transfer-Encoding").getFirst().equalsIgnoreCase("chunked")) {
                var bytes=new ByteArrayOutputStream();
                for(;;){String text=line(input).split(";")[0];int n=Integer.parseInt(text,16);if(n==0){while(!line(input).isEmpty()){}break;}bytes.write(input.readNBytes(n));line(input);if(bytes.size()>22*1024*1024){throw new IOException("Response bound");}}data=bytes.toByteArray();
            }else if(headers.containsKey("Content-Length")){int size=Integer.parseInt(headers.get("Content-Length").getFirst());if(size>22*1024*1024){throw new IOException("Response bound");}data=input.readNBytes(size);if(data.length!=size){throw new EOFException("Short backend response");}}
            else{data=input.readNBytes(22*1024*1024);}
            return new Response(status,headers,data);
        }
    }
    static String line(InputStream input)throws IOException {
        var bytes=new ByteArrayOutputStream();int n;
        while((n=input.read())!=-1){if(n=='\n'){break;}if(n!='\r'){bytes.write(n);}if(bytes.size()>16384){throw new IOException("Header bound");}}
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }
    @Override public void close(){release.countDown();server.stop(0);workers.shutdownNow();try{workers.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
}
