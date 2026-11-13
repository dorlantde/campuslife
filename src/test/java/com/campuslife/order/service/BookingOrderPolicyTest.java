package com.campuslife.order.service;
import com.campuslife.order.dto.BookingCreate;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class BookingOrderPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private BookingCreate body(String start,String note) { return json.convertValue(Map.of("serviceId","1","barberId","2","startAt",start,"expectedServiceVersion","0","expectedPayableAmount",3000,"note",note),BookingCreate.class); }
    @Test void equivalentTimesAndTrimmedNoteHaveIdenticalDigest() {
        var first=BookingOrderPolicy.parse(body("2026-10-23T01:00:00Z","  模拟预约  "));
        var second=BookingOrderPolicy.parse(body("2026-10-23T09:00:00+08:00","模拟预约"));
        assertThat(first).isEqualTo(second);assertThat(first.hash()).hasSize(64);
        assertThat(BookingOrderPolicy.parse(body("2026-10-23T01:15:00Z","模拟预约")).hash()).isNotEqualTo(first.hash());
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-23T09:00:00","2026-10-23T01:01:00Z","2026-10-23T01:00:01Z","2026-10-23T01:00:00.001Z","2026-02-30T01:00:00Z","2026-10-23T01:00:60Z"})
    void invalidOrUnalignedTimestampIsRejected(String start) { assertThatThrownBy(()->BookingOrderPolicy.parse(body(start,""))).hasMessageContaining("字段"); }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","9223372036854775808"," 1","1.0"})
    void identifiersAreStrict(String id) { assertThatThrownBy(()->BookingOrderPolicy.id(id)).hasMessageContaining("字段"); }
    @Test void exactTwoHourBoundaryPreservesSubmillisecondPrecision() {
        var start=Instant.parse("2026-10-23T01:00:00Z");var now=start.minusSeconds(7200);
        assertThatCode(()->BookingOrderPolicy.time(start,now)).doesNotThrowAnyException();
        assertThatThrownBy(()->BookingOrderPolicy.time(start,now.plusNanos(1))).hasMessageContaining("提前时间");
    }
    @Test void keysAreAsciiCaseSensitiveAndBounded() {
        assertThat(BookingOrderPolicy.key("Key-A_1")).isEqualTo("Key-A_1");
        for (String key : new String[]{"中","x".repeat(65)," invalid",""}) {
            assertThatThrownBy(()->BookingOrderPolicy.key(key)).hasMessageContaining("Idempotency-Key");
        }
    }
    @ParameterizedTest @ValueSource(strings={"\"3000\"","3000.0","true","0","-1","100001"})
    void moneyMustBeAnIntegerInTheApprovedRange(String value) {
        var base=body("2026-10-23T01:00:00Z","");
        var changed=new BookingCreate(base.serviceId(),base.barberId(),base.startAt(),base.expectedServiceVersion(),json.readTree(value),base.note());
        assertThatThrownBy(()->BookingOrderPolicy.parse(changed)).hasMessageContaining("字段");
    }
    @Test void amountVersionAndUnicodeNotesStayPartOfCanonicalRequest() {
        var raw=json.valueToTree(Map.of("serviceId","1","barberId","2","startAt","2026-10-23T01:00:00Z","expectedServiceVersion","0","expectedPayableAmount",3000,"note","😀".repeat(300)));
        assertThat(BookingOrderPolicy.parse(json.treeToValue(raw,BookingCreate.class)).note().codePointCount(0,600)).isEqualTo(300);
        assertThatThrownBy(()->BookingOrderPolicy.parse(body("2026-10-23T01:00:00Z","😀".repeat(301)))).hasMessageContaining("字段");
    }
}
