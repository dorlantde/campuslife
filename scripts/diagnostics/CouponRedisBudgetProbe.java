package diagnostic;

import io.lettuce.core.*;
import io.lettuce.core.resource.DefaultClientResources;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;

/** Isolated mechanism probe. No application context, SQL, business commands or credential output. */
public final class CouponRedisBudgetProbe {
    static void require(boolean value) { if (!value) throw new AssertionError("BUDGET_PROBE_ASSERTION"); }
    static String types(Throwable t) {
        var names = new ArrayList<String>();
        for (int i=0;t!=null && i<16;i++,t=t.getCause()) names.add(t.getClass().getName());
        return String.join("->",names);
    }
    static boolean frame(Throwable t,String fragment) {
        for(int i=0;t!=null && i<16;i++,t=t.getCause())
            for(var f:t.getStackTrace()) if(f.getClassName().contains(fragment)) return true;
        return false;
    }
    static void info(LettuceConnectionFactory factory) {
        try(var c=factory.getConnection()) { require(c.serverCommands().info("server").getProperty("run_id")!=null); }
    }
    static void resp(OutputStream out,String... parts) throws IOException {
        out.write(("*"+parts.length+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String p:parts) {
            byte[] b=p.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(("$"+b.length+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(b);out.write(new byte[]{13,10});
        }
        out.flush();
    }
    static void pause(String password) throws IOException {
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress("budget-redis",6379),300);socket.setSoTimeout(1500);
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
            resp(socket.getOutputStream(),"AUTH","redis_ops",password);require("+OK".equals(reader.readLine()));
            resp(socket.getOutputStream(),"CLIENT","PAUSE","2500","ALL");require("+OK".equals(reader.readLine()));
        }
    }
    static final class DelayProxy implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,4,InetAddress.getLoopbackAddress());
        final ExecutorService pool=Executors.newCachedThreadPool();
        final List<Socket> sockets=new CopyOnWriteArrayList<>();
        final AtomicBoolean delayed=new AtomicBoolean();
        DelayProxy() throws IOException {
            pool.submit(()->{try {
                while(!server.isClosed()) {
                    Socket client=server.accept(),upstream=new Socket();sockets.add(client);sockets.add(upstream);
                    upstream.connect(new InetSocketAddress("budget-redis",6379),300);
                    pool.submit(()->copy(client,upstream,false));pool.submit(()->copy(upstream,client,true));
                }
            } catch(IOException ignored) {}});
        }
        void copy(Socket from,Socket to,boolean delay) {
            try {
                byte[] b=new byte[8192];int n;
                while((n=from.getInputStream().read(b))!=-1) {
                    if(delay && delayed.compareAndSet(false,true)) Thread.sleep(1200);
                    to.getOutputStream().write(b,0,n);to.getOutputStream().flush();
                }
            } catch(IOException|InterruptedException ignored) { if(Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt(); }
        }
        public void close() throws IOException {
            server.close();for(var s:sockets) try{s.close();}catch(IOException ignored){}pool.shutdownNow();
        }
    }
    public static void main(String[] args) throws Exception {
        String mode=args[0];require(Set.of("default-delay","candidate-delay","candidate-command","wrong-auth","unavailable").contains(mode));
        boolean defaults=mode.equals("default-delay"),delay=mode.endsWith("-delay");
        var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        DelayProxy proxy=delay?new DelayProxy():null;
        var standalone=new RedisStandaloneConfiguration(delay?"127.0.0.1":"budget-redis",delay?proxy.server.getLocalPort():6379);
        if(mode.equals("unavailable")) standalone.setPort(1);
        standalone.setUsername("coupon_app");
        String password=Files.readString(Path.of("/run/probe/app-password"));
        standalone.setPassword(RedisPassword.of(mode.equals("wrong-auth")?"intentionally-invalid-probe-value":password));
        var options=ClientOptions.builder().requestQueueSize(128).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500))).build();
        var config=LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options)
            .commandTimeout(Duration.ofMillis(defaults?500:5000)).shutdownTimeout(Duration.ofMillis(200)).build();
        var factory=new LettuceConnectionFactory(standalone,config);factory.setEagerInitialization(false);
        String failureTypes="NONE";boolean handshake=false,expiry=false;long elapsed;
        try {
            factory.afterPropertiesSet();factory.start();
            require(config.getCommandTimeout().toMillis()==(defaults?500:5000));
            require(factory.getTimeout()==(defaults?500:5000));
            var expirySource=options.getTimeoutOptions().getSource();
            require(expirySource.getTimeUnit().toMillis(expirySource.getTimeout(null))==500);
            require(options.getSocketOptions().getConnectTimeout().toMillis()==300);
            if(mode.equals("candidate-command")) {info(factory);pause(Files.readString(Path.of("/run/probe/ops-password")));}
            long start=System.nanoTime();boolean ok=false;
            try { info(factory);ok=true; }
            catch(RuntimeException failure) {
                failureTypes=types(failure);handshake=frame(failure,"RedisHandshakeHandler");expiry=frame(failure,"CommandExpiryWriter");
            }
            elapsed=(System.nanoTime()-start)/1_000_000;
            System.out.println("OBS mode="+mode+" commandSucceeded="+ok+" elapsedMs="+elapsed
                +" handshakeFailure="+handshake+" commandExpiryFailure="+expiry+" causes="+failureTypes);
            System.out.flush();
            if(mode.equals("candidate-delay")) {require(ok);require(proxy.delayed.get());}
            else {
                require(!ok);if(defaults)require(handshake);
                if(mode.equals("candidate-command")) {
                    // Spring/Lettuce can recreate a timeout with caller stack frames. Verify the
                    // public failure and real bound rather than an internal scheduler frame.
                    require(failureTypes.contains("RedisCommandTimeoutException"));require(elapsed>=300 && elapsed<1800);
                }
                if(mode.equals("wrong-auth"))require(failureTypes.contains("RedisCommandExecutionException"));
                if(mode.equals("unavailable"))require(failureTypes.contains("ConnectException"));
                require(elapsed<12000);
            }
            System.out.println("PROBE mode="+mode+" passed=true elapsedMs="+elapsed+" configuredInitializationMs="+(defaults?500:5000)
                +" commandExpiryMs=500 tcpMs=300 handshakeFailure="+handshake+" commandExpiryFailure="+expiry+" causes="+failureTypes);
        } finally {
            factory.destroy();resources.shutdown().get(5,TimeUnit.SECONDS);if(proxy!=null)proxy.close();
        }
    }
}
