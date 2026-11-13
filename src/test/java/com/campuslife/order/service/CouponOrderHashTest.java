package com.campuslife.order.service;
import com.campuslife.order.dto.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CouponOrderHashTest {
 private final ObjectMapper json=new ObjectMapper();
 private String digest(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
 @Test void historicalBookingHashAndNullRemainByteCompatible()throws Exception{
  var body=json.readValue("{\"serviceId\":\"1\",\"barberId\":\"2\",\"startAt\":\"2026-10-31T02:00:00Z\",\"expectedServiceVersion\":\"0\",\"expectedPayableAmount\":3000}",BookingCreate.class);
  String legacy=digest("1\n2\n2026-10-31T02:00:00Z\n0\n3000\n-1:");assertThat(BookingOrderPolicy.parse(body).hash()).isEqualTo(legacy);
  var node=json.valueToTree(body);((tools.jackson.databind.node.ObjectNode)node).putNull("userCouponId");assertThat(BookingOrderPolicy.parse(json.treeToValue(node,BookingCreate.class)).hash()).isEqualTo(legacy);
  ((tools.jackson.databind.node.ObjectNode)node).put("userCouponId","3");assertThat(BookingOrderPolicy.parse(json.treeToValue(node,BookingCreate.class)).hash()).isNotEqualTo(legacy);
 }
 @Test void historicalPrintHashAndNullRemainByteCompatible()throws Exception{
  var body=json.readValue("{\"serviceId\":\"1\",\"fileId\":\"2\",\"paperSize\":\"A4\",\"colorMode\":\"BW\",\"sideMode\":\"DOUBLE\",\"copies\":2,\"expectedPriceVersion\":\"0\",\"expectedPayableAmount\":3000}",PrintCreate.class);
  String legacy=digest("PRINT\n1\n2\nA4\nBW\nDOUBLE\n2\n0\n3000\n-1:");assertThat(PrintOrderPolicy.parse(body).hash()).isEqualTo(legacy);
  var node=json.valueToTree(body);((tools.jackson.databind.node.ObjectNode)node).putNull("userCouponId");assertThat(PrintOrderPolicy.parse(json.treeToValue(node,PrintCreate.class)).hash()).isEqualTo(legacy);
  ((tools.jackson.databind.node.ObjectNode)node).put("userCouponId","3");assertThat(PrintOrderPolicy.parse(json.treeToValue(node,PrintCreate.class)).hash()).isNotEqualTo(legacy);
 }
}
