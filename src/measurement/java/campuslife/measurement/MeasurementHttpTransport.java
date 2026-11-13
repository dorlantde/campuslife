package campuslife.measurement;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** One shared HTTP/1.1 client, full-body deadline, no redirect/retry or raw response logging. */
final class MeasurementHttpTransport implements MeasurementDriver.Transport,AutoCloseable {
    static final int MAX_BODY=256*1024;
    private final URI base;
    private final ExecutorService callbacks=Executors.newFixedThreadPool(4,Thread.ofPlatform().name("measurement-http-",0).factory());
    private final HttpClient client;
    private volatile boolean closed;
    private MeasurementHttpTransport(URI base,boolean controlled){
        MeasurementScenario.need(base.getScheme().equals("http")&&base.getRawUserInfo()==null&&base.getQuery()==null
                &&base.getFragment()==null&&(base.getPath().isEmpty()||base.getPath().equals("/")),"BASE_URI");
        MeasurementScenario.need(controlled?base.getHost().equals("127.0.0.1")&&base.getPort()>0:
                base.getHost().equals("nginx")&&base.getPort()==8080,"PRIVATE_EDGE_ONLY");
        this.base=base;
        client=HttpClient.newBuilder().executor(callbacks).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
    }
    static MeasurementHttpTransport privateEdge(){return new MeasurementHttpTransport(URI.create("http://nginx:8080"),false);}
    static MeasurementHttpTransport controlled(URI base){return new MeasurementHttpTransport(base,true);}
    @Override public MeasurementDriver.Reply exchange(MeasurementDriver.Call call,long deadline)throws MeasurementDriver.TransportFailure {
        if(closed)throw new MeasurementDriver.TransportFailure("CLIENT_CLOSED");
        MeasurementScenario.need(call.path().startsWith("/api/v1/")&&!call.path().startsWith("//")&&!call.path().contains("#"),"REQUEST_PATH");
        long left=deadline-System.nanoTime();if(left<=0)throw new MeasurementDriver.TransportFailure("DEADLINE_BEFORE_SEND");
        HttpRequest.Builder request=HttpRequest.newBuilder(base.resolve(call.path())).timeout(Duration.ofNanos(left)).header("Accept","application/json");
        if(!call.token().isEmpty())request.header("Authorization","Bearer "+call.token());
        if(!call.key().isEmpty())request.header("Idempotency-Key",call.key());
        if(call.method().equals("POST"))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(call.body()));else request.GET();
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try{
            future=client.sendAsync(request.build(),ignored->new LimitedBody());
            left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException();
            HttpResponse<byte[]> response=future.get(left,TimeUnit.NANOSECONDS);
            Map<String,String> headers=new HashMap<>();
            for(String name:List.of("location","idempotency-replayed"))response.headers().firstValue(name).ifPresent(v->headers.put(name,v));
            return new MeasurementDriver.Reply(response.statusCode(),Map.copyOf(headers),response.body());
        }catch(TimeoutException e){throw new MeasurementDriver.TransportFailure("HTTP_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new MeasurementDriver.TransportFailure("INTERRUPTED");}
        catch(ExecutionException e){
            Throwable root=e.getCause();while(root!=null&&root.getCause()!=null)root=root.getCause();
            throw new MeasurementDriver.TransportFailure(root instanceof BodyLimit?"BODY_LIMIT":"HTTP_TRANSPORT");
        }catch(IllegalArgumentException e){throw new MeasurementDriver.TransportFailure("REQUEST_REFUSED");}
        finally{if(future!=null&&!future.isDone())future.cancel(true);}
    }
    static final class BodyLimit extends IOException {}
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer b:buffers){
                if((long)bytes.size()+b.remaining()>MAX_BODY){subscription.cancel();result.completeExceptionally(new BodyLimit());return;}
                byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
    @Override public void close(){
        closed=true;client.shutdownNow();
        try{MeasurementScenario.need(client.awaitTermination(Duration.ofSeconds(2)),"HTTP_CLIENT_NOT_CLOSED");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new MeasurementScenario.Invalid("HTTP_CLOSE_INTERRUPTED");}
        finally{callbacks.shutdownNow();try{callbacks.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
}
