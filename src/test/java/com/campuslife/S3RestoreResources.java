package com.campuslife;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Owns only a new restore database/container/volume/network, all under an explicit generated fixture root. */
final class S3RestoreResources implements AutoCloseable {
    static final String IMAGE="chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8";
    final Path root;
    final String identity=UUID.randomUUID().toString().replace("-",""),name="campuslife-s3-test-"+identity;
    final String dbPassword=UUID.randomUUID()+"Aa1!";
    final ObjectMapper json=new ObjectMapper();
    final List<String> helpers=new ArrayList<>();
    final int sqlPort=port(),s3Port=port();
    Process mysql;
    boolean network,volume,container;
    S3RestoreResources(Path root)throws IOException {
        this.root=root.toAbsolutePath().normalize();
        if(!this.root.startsWith(Path.of(".cache/s3-recovery").toAbsolutePath().normalize())||!"campuslife_it".equals(System.getenv("DB_NAME"))){throw new IllegalArgumentException("Owned restore root/database required");}
        Files.createDirectories(this.root);
    }
    static int port(){try(var s=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){return s.getLocalPort();}catch(IOException ex){throw new UncheckedIOException(ex);}}
    static String env(String n){return Objects.requireNonNull(System.getenv(n),"Missing owned fixture parameter "+n);}
    String sqlUrl(){return "jdbc:mysql://127.0.0.1:"+sqlPort+"/campuslife_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=PREFERRED&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=10000";}
    String endpoint(){return "http://127.0.0.1:"+s3Port;}
    String command(String... args)throws Exception {
        var log=root.resolve("command-"+UUID.randomUUID()+".log");var p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {if(!p.waitFor(60,TimeUnit.SECONDS)){throw new IOException("Owned restore command timed out: "+args[0]);}assertThat(p.exitValue()).as("Owned command diagnostics: %s",log).isZero();return Files.readString(log);}
        finally{if(p.isAlive()){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}}
    }
    void sourceGuard()throws Exception {
        String source=env("S3_PROBE_NAME"),id=env("S3_PROBE_IDENTITY");assertThat(source).isEqualTo("campuslife-s3-test-"+id);assertThat(id).matches("[a-f0-9]{32}");
        var label=json.readTree(command("docker","inspect","--format","{{json .Config.Labels}}",source));assertThat(label.path("campuslife.s3.identity").asString()).isEqualTo(id);assertThat(label.path("campuslife.s3.kind").asString()).isEqualTo("test");
    }
    void guard(String type,String resource)throws Exception {
        String format=type.equals("container")?"{{json .Config.Labels}}":"{{json .Labels}}";
        var label=json.readTree(type.equals("container")?command("docker","inspect","--format",format,resource):command("docker",type,"inspect","--format",format,resource));
        assertThat(label.path("campuslife.s3.identity").asString()).isEqualTo(identity);assertThat(label.path("campuslife.s3.kind").asString()).isEqualTo("test");
    }
    void archive(String sourceVolume,boolean extract)throws Exception {
        String helper="campuslife-s3-copy-"+UUID.randomUUID().toString().replace("-","");helpers.add(helper);
        command("docker","run","--rm","--name",helper,"--label","campuslife.s3.identity="+identity,"--label","campuslife.s3.kind=test",
                "--network","none","--mount","type=volume,source="+sourceVolume+",target=/data"+(extract?"":",readonly"),
                "--mount","type=bind,source="+root+",target=/backup","--entrypoint","sh",IMAGE,"-c",extract?"tar -xzf /backup/storage.tgz -C /data":"tar -czf /backup/storage.tgz -C /data .");
    }
    void snapshotStorage()throws Exception {
        sourceGuard();command("docker","stop",env("S3_PROBE_NAME"));
        assertThat(json.readTree(command("docker","inspect","--format","{{json .State.Running}}",env("S3_PROBE_NAME"))).asBoolean()).isFalse();
        archive(env("S3_PROBE_NAME"),false);assertThat(Files.size(root.resolve("storage.tgz"))).isGreaterThan(0);
        Path config=Path.of(".cache/s3-it",env("S3_PROBE_IDENTITY"),"s3.json").toAbsolutePath();
        Files.copy(config,root.resolve("s3.json"));
    }
    void restoreStorage()throws Exception {
        command("docker","network","create","--driver","bridge","--label","campuslife.s3.identity="+identity,"--label","campuslife.s3.kind=test",name);network=true;
        command("docker","volume","create","--label","campuslife.s3.identity="+identity,"--label","campuslife.s3.kind=test",name);volume=true;
        guard("volume",name);archive(name,true);
        command("docker","create","--name",name,"--network",name,"--label","campuslife.s3.identity="+identity,"--label","campuslife.s3.kind=test","--memory","768m","--cpus","2",
                "-p","127.0.0.1:"+s3Port+":8333","--mount","type=volume,source="+name+",target=/data","--mount","type=bind,source="+root.resolve("s3.json")+",target=/etc/campuslife-s3.json,readonly","--entrypoint","weed",IMAGE,
                "mini","-dir=/data","-ip=127.0.0.1","-ip.bind=0.0.0.0","-s3.config=/etc/campuslife-s3.json","-s3.autoCreateBucket=false","-webdav=false","-admin.ui=false","-s3.port.iceberg=0","-s3.port.lance=0","-volume.max=16");container=true;
        guard("container",name);command("docker","start",name);
        var ports=json.readTree(command("docker","inspect","--format","{{json .NetworkSettings.Ports}}",name));assertThat(ports.path("8333/tcp").size()).isEqualTo(1);assertThat(ports.path("8333/tcp").get(0).path("HostIp").asString()).isEqualTo("127.0.0.1");assertThat(ports.path("8333/tcp").get(0).path("HostPort").asString()).isEqualTo(Integer.toString(s3Port));
    }
    void dumpDatabase()throws Exception {
        var b=new ProcessBuilder(env("S3_RECOVERY_MYSQLDUMP"),"--no-defaults","--protocol=TCP","--host=127.0.0.1","--port="+env("DB_PORT"),"--user="+env("DB_USERNAME"),"--single-transaction","--skip-lock-tables","--set-gtid-purged=OFF","--no-tablespaces","--hex-blob","--default-character-set=utf8mb4","campuslife_it");
        b.environment().put("MYSQL_PWD",env("DB_PASSWORD"));var p=b.redirectOutput(root.resolve("database.sql").toFile()).redirectError(root.resolve("dump.stderr.log").toFile()).start();
        try{assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();assertThat(Files.size(root.resolve("database.sql"))).isGreaterThan(0);}finally{if(p.isAlive()){p.destroyForcibly();}}
    }
    Connection adminSql()throws SQLException {return DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+sqlPort+"/mysql?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectTimeout=1000&socketTimeout=5000","root","");}
    void restoreDatabase()throws Exception {
        Path data=root.resolve("mysql-data"),tmp=root.resolve("mysql-tmp");Files.createDirectories(data);Files.createDirectories(tmp);
        Path exe=Path.of(env("S3_RECOVERY_MYSQLD"));String base=exe.getParent().getParent().toString();
        command(exe.toString(),"--no-defaults","--basedir="+base,"--datadir="+data,"--initialize-insecure");
        mysql=new ProcessBuilder(exe.toString(),"--no-defaults","--basedir="+base,"--datadir="+data,"--tmpdir="+tmp,"--bind-address=127.0.0.1","--port="+sqlPort,"--mysqlx=OFF","--skip-log-bin","--default-time-zone=+00:00","--console")
                .redirectErrorStream(true).redirectOutput(root.resolve("mysql-server.log").toFile()).start();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean ready=false;
        while(System.nanoTime()<end){try(var c=adminSql()){try(var q=c.createStatement()){var rs=q.executeQuery("SELECT @@datadir");rs.next();assertThat(Path.of(rs.getString(1)).toAbsolutePath().normalize()).isEqualTo(data);q.execute("CREATE DATABASE campuslife_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");q.execute("CREATE USER 'campuslife'@'127.0.0.1' IDENTIFIED BY '"+dbPassword+"'");q.execute("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON campuslife_it.* TO 'campuslife'@'127.0.0.1'");}ready=true;break;}catch(SQLException ex){Thread.sleep(100);}}
        assertThat(ready).as("Owned restored MySQL starts").isTrue();
        var b=new ProcessBuilder(env("S3_RECOVERY_MYSQL"),"--no-defaults","--protocol=TCP","--host=127.0.0.1","--port="+sqlPort,"--user=root","--skip-password","--default-character-set=utf8mb4","campuslife_it");b.environment().remove("MYSQL_PWD");
        var p=b.redirectInput(root.resolve("database.sql").toFile()).redirectOutput(root.resolve("restore.stdout.log").toFile()).redirectError(root.resolve("restore.stderr.log").toFile()).start();
        try{assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).as("Dump import, diagnostics %s",root).isZero();}finally{if(p.isAlive()){p.destroyForcibly();}}
    }
    static void copyTree(Path source,Path target)throws IOException {
        Files.createDirectories(target);
        try(var paths=Files.walk(source)){for(var p:paths.toList()){
            var attributes=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(attributes.isSymbolicLink()||attributes.isOther()){throw new IOException("Linked backup entry rejected");}
            var dest=target.resolve(source.relativize(p));if(attributes.isDirectory()){Files.createDirectories(dest);}else{Files.copy(p,dest);}
        }}
    }
    @Override public void close()throws Exception {
        Exception problem=null;
        if(mysql!=null){
            var owned=ProcessHandle.allProcesses().filter(p->p.info().arguments().map(a->Arrays.asList(a).contains("--datadir="+root.resolve("mysql-data"))).orElse(false)).toList();
            try(var c=adminSql();var s=c.createStatement()){s.execute("SHUTDOWN");}catch(SQLException ignored){}
            for(var p:owned){try{p.onExit().get(10,TimeUnit.SECONDS);}catch(Exception ex){if(p.isAlive()){p.destroyForcibly();p.onExit().get(10,TimeUnit.SECONDS);}}}
            if(mysql.isAlive()){mysql.destroyForcibly();mysql.waitFor(10,TimeUnit.SECONDS);}
        }
        for(var helper:helpers){String result=command("docker","ps","-aq","--filter","name=^"+helper+"$","--filter","label=campuslife.s3.identity="+identity);if(!result.isBlank()){guard("container",helper);command("docker","rm","-f",helper);}}
        try{if(container){guard("container",name);command("docker","logs",name);command("docker","rm","-f",name);}if(volume){guard("volume",name);command("docker","volume","rm",name);}if(network){guard("network",name);command("docker","network","rm",name);}}
        catch(Exception ex){problem=ex;}
        if(problem!=null){throw problem;}
    }
}
