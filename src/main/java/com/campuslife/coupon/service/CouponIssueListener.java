package com.campuslife.coupon.service;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile("mysql")
public class CouponIssueListener implements ChannelAwareMessageListener {
 private final CouponIssuance delivery;
 public CouponIssueListener(CouponIssuance delivery){this.delivery=delivery;}
 @Override public void onMessage(Message message,Channel channel)throws IOException {
  long tag=message.getMessageProperties().getDeliveryTag();
  for(int attempt=1;attempt<=3;attempt++){
   try{if(!"application/json".equals(message.getMessageProperties().getContentType())){throw new CouponIssuance.InvalidMessage();}
    delivery.consume(message.getMessageProperties().getMessageId(),message.getBody());
   }catch(CouponIssuance.InvalidMessage ex){channel.basicReject(tag,false);return;}
   catch(DataAccessException|org.springframework.transaction.TransactionException ex){if(attempt==3){channel.basicReject(tag,false);return;}try{Thread.sleep(attempt*1000L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Coupon issuance retry interrupted");}continue;}
   catch(RuntimeException ex){channel.basicReject(tag,false);return;}
   // consume is a separate proxied transaction; it has committed before ACK.
   channel.basicAck(tag,false);return;
  }
 }
}
