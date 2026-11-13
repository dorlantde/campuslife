package com.campuslife;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Standalone protocol fixture: no Spring context, database, development credentials, or business file IO. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_S3_IT",matches="owned-isolated-fixture")
class S3CompatibilityProbeTest {
 final ObjectMapper json=new ObjectMapper();final List<Map<String,Object>> checks=new ArrayList<>();
 final AtomicInteger transmissions=new AtomicInteger();final Set<String> checksumHeaders=ConcurrentHashMap.newKeySet();
 S3Client admin,app,anonymous,wrong;String name,identity,bucket,namespace,endpoint,mainKey,foreignKey,shaKey;
 Path output,sample;String sampleSha;long testStarted;final Map<String,Object> observations=new LinkedHashMap<>();
 @RegisterExtension final TestWatcher evidence=new TestWatcher(){
  @Override public void testSuccessful(ExtensionContext c){record(c,"passed",null);}
  @Override public void testFailed(ExtensionContext c,Throwable cause){record(c,"failed",cause.getClass().getSimpleName());}
  void record(ExtensionContext c,String outcome,String failure){var row=new LinkedHashMap<String,Object>();row.put("case",c.getDisplayName());row.put("outcome",outcome);row.put("ms",(System.nanoTime()-testStarted)/1_000_000);if(failure!=null){row.put("failure",failure);}checks.add(row);}
 };
 @BeforeEach void start(){testStarted=System.nanoTime();}
 @BeforeAll void prepare()throws Exception{
  name=env("S3_PROBE_NAME");identity=env("S3_PROBE_IDENTITY");bucket=env("S3_PROBE_BUCKET");namespace=env("S3_PROBE_NAMESPACE");endpoint=env("S3_PROBE_ENDPOINT");output=Path.of(env("S3_PROBE_OUTPUT")).toAbsolutePath().normalize();
  assertThat(output).startsWith(Path.of(".cache/s3-it").toAbsolutePath().normalize());assertThat(identity).matches("[a-f0-9]{32}");assertThat(name).isEqualTo("campuslife-s3-test-"+identity);assertThat(bucket).isEqualTo("campuslife-"+identity);assertThat(namespace).matches("[a-f0-9-]{36}");
  var uri=URI.create(endpoint);assertThat(uri.getScheme()).isEqualTo("http");assertThat(uri.getHost()).isEqualTo("127.0.0.1");guard();
  admin=client(credentials("ADMIN"));app=client(credentials("APP"));anonymous=client(AnonymousCredentialsProvider.create());wrong=client(StaticCredentialsProvider.create(AwsBasicCredentials.create(env("S3_PROBE_APP_KEY"),"wrong-fixture-secret")));
  await(()->admin.listBuckets(),90);
  admin.createBucket(b->b.bucket(bucket));admin.createBucket(b->b.bucket(bucket+"-other"));
  admin.putObject(b->b.bucket(bucket).key("_storage-id"),RequestBody.fromString(namespace));
  admin.putObject(b->b.bucket(bucket).key("_storage-id-shadow"),RequestBody.fromString("not the identity marker"));
  foreignKey="foreign/objects/"+leaf();admin.putObject(b->b.bucket(bucket).key(foreignKey),RequestBody.fromString("foreign-private-object"));
  String policy=json.writeValueAsString(Map.of("Version","2012-10-17","Statement",List.of(Map.of("Sid","ProtectNamespaceFolders","Effect","Deny","Principal","*","Action","s3:DeleteObject","Resource",List.of("arn:aws:s3:::"+bucket+"/"+namespace+"/objects/","arn:aws:s3:::"+bucket+"/"+namespace+"/objects","arn:aws:s3:::"+bucket+"/"+namespace+"/")),Map.of("Sid","ExactIdentityMarkerOnly","Effect","Deny","Principal","*","Action","s3:GetObject","Resource","arn:aws:s3:::"+bucket+"/_storage-id?*"))));
  admin.putBucketPolicy(b->b.bucket(bucket).policy(policy));
  sample=Files.createTempFile(output,"sample-",".bin");var digest=MessageDigest.getInstance("SHA-256");var random=new Random(20261102);byte[] block=new byte[8192];
  try(var stream=Files.newOutputStream(sample)){for(int n=0;n<2560;n++){random.nextBytes(block);stream.write(block);digest.update(block);}}
  assertThat(Files.size(sample)).isEqualTo(20L*1024*1024);sampleSha=HexFormat.of().formatHex(digest.digest());mainKey=key(leaf());shaKey=key(leaf());
  observations.put("sdkJar",Path.of(S3Client.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString());observations.put("sampleBytes",Files.size(sample));observations.put("sampleSha256",sampleSha);
 }
 AwsCredentialsProvider credentials(String role){return StaticCredentialsProvider.create(AwsBasicCredentials.create(env("S3_PROBE_"+role+"_KEY"),env("S3_PROBE_"+role+"_SECRET")));}
 S3Client client(AwsCredentialsProvider provider){return client(provider,endpoint,Duration.ofSeconds(30),Duration.ofSeconds(15),Duration.ofSeconds(5));}
 S3Client client(AwsCredentialsProvider provider,String address,Duration total,Duration attempt,Duration socket){
  return S3Client.builder().credentialsProvider(provider).endpointOverride(URI.create(address)).region(Region.US_EAST_1).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
   .httpClientBuilder(Apache5HttpClient.builder().maxConnections(4).connectionTimeout(Duration.ofSeconds(2)).connectionAcquisitionTimeout(Duration.ofSeconds(2)).socketTimeout(socket).proxyConfiguration(software.amazon.awssdk.http.apache5.ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(false).build()))
   .overrideConfiguration(c->c.apiCallTimeout(total).apiCallAttemptTimeout(attempt).retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build()).addExecutionInterceptor(new ExecutionInterceptor(){
    @Override public void beforeTransmission(Context.BeforeTransmission c,ExecutionAttributes a){transmissions.incrementAndGet();c.httpRequest().headers().keySet().stream().filter(k->k.toLowerCase(Locale.ROOT).contains("checksum")||k.equalsIgnoreCase("x-amz-trailer")).forEach(checksumHeaders::add);}
   })).build();
 }
 static String env(String key){return Objects.requireNonNull(System.getenv(key),"Missing owned fixture setting: "+key);}
 static String leaf(){return UUID.randomUUID().toString().replace("-","")+".pdf";}
 String key(String value){if(value==null||!value.matches("[a-f0-9]{32}[.]pdf")){throw new IllegalArgumentException("Exact opaque file key required");}return namespace+"/objects/"+value;}
 void guard()throws Exception{
  var labels=json.readTree(docker("inspect","--format","{{json .Config.Labels}}",name));assertThat(labels.path("campuslife.s3.identity").asString()).isEqualTo(identity);assertThat(labels.path("campuslife.s3.kind").asString()).isEqualTo("test");
  var ports=json.readTree(docker("inspect","--format","{{json .HostConfig.PortBindings}}",name));assertThat(ports.size()).isEqualTo(1);assertThat(ports.path("8333/tcp").get(0).path("HostIp").asString()).isEqualTo("127.0.0.1");assertThat(ports.path("8333/tcp").get(0).path("HostPort").asString()).isEqualTo(Integer.toString(URI.create(endpoint).getPort()));
  var network=json.readTree(docker("network","inspect","--format","{{json .}}",name));assertThat(network.path("Labels").path("campuslife.s3.identity").asString()).isEqualTo(identity);assertThat(network.path("Driver").asString()).isEqualTo("bridge");assertThat(network.path("Internal").asBoolean()).isFalse();assertThat(network.path("Containers").size()).isEqualTo(1);
  var effective=json.readTree(docker("inspect","--format","{{json .NetworkSettings.Ports}}",name));assertThat(effective.path("8333/tcp").size()).isEqualTo(1);assertThat(effective.path("8333/tcp").get(0).path("HostIp").asString()).isEqualTo("127.0.0.1");assertThat(effective.path("8333/tcp").get(0).path("HostPort").asString()).isEqualTo(Integer.toString(URI.create(endpoint).getPort()));
  var volume=json.readTree(docker("volume","inspect","--format","{{json .Labels}}",name));assertThat(volume.path("campuslife.s3.identity").asString()).isEqualTo(identity);
 }
 String docker(String... args)throws Exception{var command=new ArrayList<String>();command.add("docker");command.addAll(List.of(args));var p=new ProcessBuilder(command).redirectErrorStream(true).start();assertThat(p.waitFor(45,TimeUnit.SECONDS)).as("Owned docker command completes").isTrue();assertThat(p.exitValue()).as("Owned docker command succeeds").isZero();return new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
 void await(Callable<?> ready,int seconds)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);Throwable last=null;while(System.nanoTime()<end){try{ready.call();return;}catch(S3Exception e){if(e.statusCode()<500){throw e;}last=e;Thread.sleep(250);}catch(Exception e){last=e;Thread.sleep(250);}}throw new AssertionError("Owned S3 readiness timed out",last);}
 void denied(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(S3Exception.class,e->assertThat(e.statusCode()).isEqualTo(403));}
 void assertMainBytes()throws Exception{var digest=MessageDigest.getInstance("SHA-256");long bytes=0;try(var in=app.getObject(b->b.bucket(bucket).key(mainKey).checksumMode(ChecksumMode.ENABLED))){assertThat(in.response().contentLength()).isEqualTo(Files.size(sample));byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){bytes+=n;digest.update(block,0,n);}}assertThat(bytes).isEqualTo(Files.size(sample));assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(sampleSha);}

 @Test @Order(1) void signedTwentyMiBPutHeadGetWithDefaultChecksum()throws Exception{
  var put=app.putObject(b->b.bucket(bucket).key(mainKey).contentType("application/pdf"),RequestBody.fromFile(sample));assertThat(put.sdkHttpResponse().isSuccessful()).isTrue();assertThat(app.headObject(b->b.bucket(bucket).key(mainKey)).contentLength()).isEqualTo(20L*1024*1024);assertMainBytes();assertThat(checksumHeaders).isNotEmpty();observations.put("checksumHeaders",new TreeSet<>(checksumHeaders));
 }
 @Test @Order(2) void explicitSha256ChecksumIsAcceptedAndReturned()throws Exception{
  byte[] bytes="fixture SHA256 round trip".getBytes(StandardCharsets.UTF_8);String hash=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
  app.putObject(b->b.bucket(bucket).key(shaKey).checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(hash),RequestBody.fromBytes(bytes));
  try(var in=app.getObject(b->b.bucket(bucket).key(shaKey).checksumMode(ChecksumMode.ENABLED))){assertThat(in.readAllBytes()).isEqualTo(bytes);assertThat(in.response().checksumSHA256()).isEqualTo(hash);}
 }
 @Test @Order(3) void incorrectChecksumIsRejectedWithoutCreatingObject(){
  String target=key(leaf());assertThatThrownBy(()->app.putObject(b->b.bucket(bucket).key(target).checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(Base64.getEncoder().encodeToString(new byte[32])),RequestBody.fromString("wrong checksum body"))).isInstanceOfSatisfying(S3Exception.class,e->{assertThat(e.statusCode()).isEqualTo(400);assertThat(e.awsErrorDetails().errorCode()).isEqualTo("BadDigest");});
  assertThatThrownBy(()->admin.headObject(b->b.bucket(bucket).key(target))).isInstanceOfSatisfying(S3Exception.class,e->assertThat(e.statusCode()).isEqualTo(404));
 }
 @Test @Order(4) void repeatedSameKeySameBytesIsRecoverable()throws Exception{app.putObject(b->b.bucket(bucket).key(mainKey),RequestBody.fromFile(sample));assertMainBytes();}
 @Test @Order(5) void applicationCanReadButNotOverwriteOrDeleteStorageMarker(){assertThat(app.getObjectAsBytes(b->b.bucket(bucket).key("_storage-id")).asUtf8String()).isEqualTo(namespace);denied(()->app.getObjectAsBytes(b->b.bucket(bucket).key("_storage-id-shadow")));denied(()->app.putObject(b->b.bucket(bucket).key("_storage-id"),RequestBody.fromString("wrong")));denied(()->app.deleteObject(b->b.bucket(bucket).key("_storage-id")));}

 @ParameterizedTest @Order(10) @ValueSource(strings={"anonymousGet","anonymousHead","anonymousPut","anonymousDelete","wrongSignature","crossPrefixGet","crossPrefixPut","crossPrefixDelete","crossBucketGet","crossBucketPut","listBuckets","listObjects","createBucket","deleteBucket","replacePolicy","deleteFolder"})
 void privateIdentityRejectsUnauthorizedOperations(String operation){
  if(operation.equals("listBuckets")){assertThat(app.listBuckets().buckets()).isEmpty();observations.put("unprivilegedListBuckets","200 empty list, not 403");return;}
  denied(()->{switch(operation){
   case "anonymousGet"->anonymous.getObjectAsBytes(b->b.bucket(bucket).key(mainKey));case "anonymousHead"->anonymous.headObject(b->b.bucket(bucket).key(mainKey));case "anonymousPut"->anonymous.putObject(b->b.bucket(bucket).key(mainKey),RequestBody.fromString("no"));case "anonymousDelete"->anonymous.deleteObject(b->b.bucket(bucket).key(mainKey));
   case "wrongSignature"->wrong.headObject(b->b.bucket(bucket).key(mainKey));case "crossPrefixGet"->app.getObjectAsBytes(b->b.bucket(bucket).key(foreignKey));case "crossPrefixPut"->app.putObject(b->b.bucket(bucket).key("foreign/"+leaf()),RequestBody.fromString("no"));case "crossPrefixDelete"->app.deleteObject(b->b.bucket(bucket).key(foreignKey));
   case "crossBucketGet"->app.headObject(b->b.bucket(bucket+"-other").key("foreign"));case "crossBucketPut"->app.putObject(b->b.bucket(bucket+"-other").key(leaf()),RequestBody.fromString("no"));case "listBuckets"->app.listBuckets();case "listObjects"->app.listObjectsV2(b->b.bucket(bucket));case "createBucket"->app.createBucket(b->b.bucket(bucket+"-denied"));case "deleteBucket"->app.deleteBucket(b->b.bucket(bucket+"-other"));case "replacePolicy"->app.putBucketPolicy(b->b.bucket(bucket).policy("{}"));case "deleteFolder"->app.deleteObject(b->b.bucket(bucket).key(namespace+"/objects/"));default->throw new IllegalArgumentException(operation);
  }});
 }
 @ParameterizedTest @Order(20) @ValueSource(strings={"","/","../bad.pdf","objects/","UPPER.pdf","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf/"})
 void unsafeExactKeysAreRejectedBeforeNetwork(String value){int before=transmissions.get();assertThatThrownBy(()->{String target=key(value);app.deleteObject(b->b.bucket(bucket).key(target));}).isInstanceOf(IllegalArgumentException.class);assertThat(transmissions.get()).isEqualTo(before);}
 @Test @Order(30) void unauthorizedWritesDidNotChangeOtherObjects()throws Exception{assertMainBytes();assertThat(admin.getObjectAsBytes(b->b.bucket(bucket).key(foreignKey)).asUtf8String()).isEqualTo("foreign-private-object");}
 @Test @Order(40) void missingExactObjectReturns404(){assertThatThrownBy(()->app.headObject(b->b.bucket(bucket).key(key(leaf())))).isInstanceOfSatisfying(S3Exception.class,e->assertThat(e.statusCode()).isEqualTo(404));}
 @Test @Order(50) void sdkTimeoutAndRetryBoundAreRealLoopbackIo()throws Exception{
  var sockets=new CopyOnWriteArrayList<Socket>();try(var server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
   var accept=Thread.ofPlatform().daemon(true).start(()->{try{while(!server.isClosed()){sockets.add(server.accept());}}catch(IOException ignored){}});
   int before=transmissions.get();long start=System.nanoTime();
   try(var blackhole=client(credentials("APP"),"http://127.0.0.1:"+server.getLocalPort(),Duration.ofMillis(1800),Duration.ofMillis(800),Duration.ofMillis(500))){assertThatThrownBy(()->blackhole.headObject(b->b.bucket(bucket).key(mainKey))).isInstanceOf(SdkClientException.class);}
   long elapsed=(System.nanoTime()-start)/1_000_000;int count=transmissions.get()-before;assertThat(count).isBetween(1,2);assertThat(elapsed).isLessThan(4000);observations.put("blackholeMs",elapsed);observations.put("blackholeAttempts",count);
   for(var socket:sockets){socket.close();}server.close();accept.join(2000);
  }
 }
 @Test @Order(60) void serviceStopFailsAndRestartRestoresOriginalBytes()throws Exception{
  guard();docker("stop","-t","3",name);
  try{assertThatThrownBy(()->app.headObject(b->b.bucket(bucket).key(mainKey))).isInstanceOf(SdkClientException.class);}finally{docker("start",name);await(()->admin.headObject(b->b.bucket(bucket).key("_storage-id")),90);}
  assertMainBytes();guard();observations.put("serviceStopStart",true);
 }
 @Test @Order(70) void containerRestartPreservesBytesAuthenticationAndBucketPolicy()throws Exception{
  guard();docker("restart","-t","3",name);await(()->admin.headObject(b->b.bucket(bucket).key("_storage-id")),90);assertMainBytes();assertThat(app.getObjectAsBytes(b->b.bucket(bucket).key("_storage-id")).asUtf8String()).isEqualTo(namespace);
  denied(()->anonymous.headObject(b->b.bucket(bucket).key(mainKey)));denied(()->app.deleteObject(b->b.bucket(bucket).key(namespace+"/objects/")));guard();observations.put("restartPersistence",true);
 }
 @Test @Order(80) void exactDeleteAndMissingDeleteAreIdempotent(){String target=key(leaf());app.putObject(b->b.bucket(bucket).key(target),RequestBody.fromString("delete only this object"));assertThat(app.deleteObject(b->b.bucket(bucket).key(target)).sdkHttpResponse().isSuccessful()).isTrue();assertThat(app.deleteObject(b->b.bucket(bucket).key(target)).sdkHttpResponse().isSuccessful()).isTrue();assertThatThrownBy(()->app.headObject(b->b.bucket(bucket).key(target))).isInstanceOfSatisfying(S3Exception.class,e->assertThat(e.statusCode()).isEqualTo(404));assertThat(app.headObject(b->b.bucket(bucket).key(mainKey)).contentLength()).isEqualTo(20L*1024*1024);}
 @AfterAll void close()throws Exception{
  for(var client:Arrays.asList(admin,app,anonymous,wrong)){if(client!=null){client.close();}}
  if(sample!=null){Files.deleteIfExists(sample);}
  if(output!=null){Files.writeString(output.resolve("probe.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("task","M04.2-A","sdk","2.57.8","cases",checks,"observations",observations,"scope","standalone S3 only; no business file/database/HTTP integration")));}
 }
}
