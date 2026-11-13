package com.campuslife.messaging.bootstrap;
import java.sql.*;
import java.time.*;
/** Explicit local, non-HTTP maintenance. Never started by the normal application. */
public final class MessageMaintenance {
 public static void main(String[] args){int code=1;try{if(args.length!=0){throw new IllegalArgumentException();}run(System.getenv("MESSAGE_ACTION"),System.getenv("MESSAGE_ID"),System.getenv("MESSAGE_REASON"));code=0;}catch(Exception ex){System.err.println("Message maintenance failed; check local database settings, identity, reason and active lease. No payload/credentials printed.");}System.exit(code);}
 private static void run(String action,String id,String reason)throws Exception{
  if(!("Inspect".equals(action)||"Replay".equals(action))||id==null||!id.matches("[a-f0-9]{32}")){throw new IllegalArgumentException();}
  String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1"),port=System.getenv().getOrDefault("DB_PORT","3306"),db=System.getenv().getOrDefault("DB_NAME","campuslife");
  if(!host.matches("[A-Za-z0-9.-]+")||!port.matches("[0-9]{1,5}")||!db.matches("[A-Za-z0-9_]+")){throw new IllegalArgumentException();}
  try(var c=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+db+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=10000",System.getenv().getOrDefault("DB_USERNAME","campuslife"),System.getenv("DB_PASSWORD"))){
   c.setAutoCommit(false);try{
    try(var q=c.prepareStatement("SELECT status,attempts,replay_count,lease_until,created_at FROM message_outbox WHERE message_id=? FOR UPDATE")){q.setString(1,id);try(var rs=q.executeQuery()){if(!rs.next()){throw new IllegalArgumentException();}
     if(action.equals("Inspect")){System.out.println("messageId="+id+" status="+rs.getString(1)+" attempts="+rs.getInt(2)+" replayCount="+rs.getInt(3));c.commit();return;}
     reason=reason==null?"":reason.strip();if(reason.codePointCount(0,reason.length())<1||reason.codePointCount(0,reason.length())>300){throw new IllegalArgumentException();}
     var now=LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);var lease=rs.getObject(4,LocalDateTime.class);if(now.isBefore(rs.getObject(5,LocalDateTime.class))||(lease!=null&&now.isBefore(lease))){throw new IllegalArgumentException();}
    }}
    try(var q=c.prepareStatement("SELECT 1 FROM message_consumption WHERE consumer_name='notification-v1' AND message_id=?")){q.setString(1,id);try(var rs=q.executeQuery()){if(rs.next()){c.commit();System.out.println("Already consumed; no replay needed.");return;}}}
    try(var q=c.prepareStatement("UPDATE message_outbox SET status='READY',attempts=0,last_attempt_at=NULL,lease_token=NULL,lease_until=NULL,published_at=NULL,next_attempt_at=UTC_TIMESTAMP(3),last_error_code=NULL,replay_count=replay_count+1,replay_reason=?,replayed_at=UTC_TIMESTAMP(3) WHERE message_id=?")){q.setString(1,reason);q.setString(2,id);if(q.executeUpdate()!=1){throw new IllegalStateException();}}
    c.commit();System.out.println("Replay scheduled with original message identity.");
   }catch(Exception ex){c.rollback();throw ex;}
  }
 }
}
