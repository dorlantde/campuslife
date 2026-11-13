package com.campuslife;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import tools.jackson.databind.*;

/** Test driver guard: replace the entire production directory, never overlay new and old Beans. */
final class PublisherComparisonClasspath {
 static final Path BASE=Path.of(".cache/m085-baseline-2026-11-01").toAbsolutePath().normalize();
 static final Path CURRENT=Path.of("target/classes").toAbsolutePath().normalize();
 static String digest(InputStream in)throws Exception{try(in){return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}}
 static String digest(Path p)throws Exception{return digest(Files.newInputStream(p));}
 static void require(boolean ok,String message){if(!ok){throw new IllegalStateException(message);}}
 static Map<String,Object> validate()throws Exception{
  var json=new ObjectMapper();var manifest=json.readTree(Files.readString(BASE.resolve("manifest.json")));
  require(manifest.path("classes").size()==376,"Unexpected old baseline file count");
  var expectedCurrent=new HashSet<String>();
  for(var item:manifest.path("classes")){Path p=BASE.resolve(item.path("path").asString().replace('\\','/')).normalize();require(p.startsWith(BASE.resolve("classes")),"Baseline path escaped root");require(digest(p).equals(item.path("sha256").asString()),"Old baseline hash changed: "+p.getFileName());
   String relative=BASE.resolve("classes").relativize(p).toString().replace('\\','/');expectedCurrent.add(relative);
   if(!relative.equals("application.properties")&&!relative.startsWith("com/campuslife/messaging/service/OutboxPublisher")){require(digest(CURRENT.resolve(relative)).equals(item.path("sha256").asString()),"Unrelated production binary changed: "+relative);}
  }
  try(var files=Files.walk(CURRENT)){for(var p:files.filter(Files::isRegularFile).toList()){String relative=CURRENT.relativize(p).toString().replace('\\','/');require(expectedCurrent.contains(relative)||relative.startsWith("com/campuslife/messaging/service/OutboxPublisher$"),"Unexpected new production file: "+relative);}}
  try(var files=Files.walk(BASE.resolve("classes"))){require(files.filter(Files::isRegularFile).count()==376,"Extra files in old production directory");}
  require(digest(BASE.resolve("campuslife-before.jar")).equals(manifest.path("jarSha256").asString()),"Old JAR hash changed");
  require(digest(Path.of("pom.xml")).equals(manifest.path("pomSha256").asString()),"Production dependencies changed");
  Path currentJar=Path.of("target/campuslife-0.0.1-SNAPSHOT.jar");
  try(var jar=new ZipFile(currentJar.toFile())){
   require(manifest.path("dependencies").size()==115,"Unexpected dependency count");
   require(jar.stream().filter(e->e.getName().startsWith("BOOT-INF/lib/")&&e.getName().endsWith(".jar")).count()==115,"New JAR dependency count changed");
   for(var item:manifest.path("dependencies")){var entry=jar.getEntry(item.path("path").asString());require(entry!=null&&digest(jar.getInputStream(entry)).equals(item.path("sha256").asString()),"New JAR dependency hash changed");}
   try(var files=Files.walk(CURRENT)){for(var p:files.filter(Files::isRegularFile).toList()){String relative=CURRENT.relativize(p).toString().replace('\\','/');var entry=jar.getEntry("BOOT-INF/classes/"+relative);require(entry!=null&&digest(jar.getInputStream(entry)).equals(digest(p)),"New JAR/classes differ: "+relative);}}
  }
  String relative="com/campuslife/messaging/service/OutboxPublisher.class";
  String oldHash=digest(BASE.resolve("classes").resolve(relative)),newHash=digest(CURRENT.resolve(relative));
  require(!oldHash.equals(newHash),"Old and new publisher unexpectedly identical");
  return Map.of("oldJarSha256",manifest.path("jarSha256").asString(),"newJarSha256",digest(currentJar),"oldPublisherSha256",oldHash,"newPublisherSha256",newHash,"oldFiles",376,"productionDependencies",115);
 }
 static String select(String cp,String version)throws Exception{
  require(Set.of("OLD","NEW").contains(version),"Unknown publisher binary");
  Path root=version.equals("OLD")?BASE.resolve("classes"):CURRENT;var parts=new ArrayList<String>();int replaced=0;
  for(String part:cp.split(java.util.regex.Pattern.quote(File.pathSeparator))){Path p=Path.of(part).toAbsolutePath().normalize();
   if(p.equals(CURRENT)){parts.add(root.toString());replaced++;}else{
    require(!p.equals(BASE.resolve("classes"))&&(!Files.isDirectory(p)||!Files.exists(p.resolve("com/campuslife/CampusLifeApplication.class"))),"Extra production directory in test classpath");
    require(!part.endsWith("campuslife-0.0.1-SNAPSHOT.jar"),"Executable app JAR must not be overlaid");parts.add(part);
   }
  }
  require(replaced==1,"Expected exactly one current production class directory");return String.join(File.pathSeparator,parts);
 }
 static Map<String,Object> childIdentity()throws Exception{
  String version=System.getenv("COUPON_BENCH_BINARY");if(version==null){return Map.of("binary","CURRENT");}
  Path expected=version.equals("OLD")?BASE.resolve("classes"):CURRENT;
  var actual=Path.of(com.campuslife.messaging.service.OutboxPublisher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
  require(actual.equals(expected),"Publisher loaded from wrong binary directory");
  for(String resource:List.of("com/campuslife/messaging/service/OutboxPublisher.class","com/campuslife/CampusLifeApplication.class","application.properties")){
   var found=Collections.list(CouponMeasurementChild.class.getClassLoader().getResources(resource));require(found.size()==1,"Mixed production resource: "+resource);
  }
  return Map.of("binary",version,"productionRoot",actual.toString(),"publisherSha256",digest(actual.resolve("com/campuslife/messaging/service/OutboxPublisher.class")));
 }
}
