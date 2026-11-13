package com.campuslife.order.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.dto.PrintCreate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class PrintOrderPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode body(){return (ObjectNode)json.readTree("{\"serviceId\":\"1\",\"fileId\":\"2\",\"paperSize\":\"A4\",\"colorMode\":\"BW\",\"sideMode\":\"DOUBLE\",\"copies\":2,\"expectedPriceVersion\":\"0\",\"expectedPayableAmount\":200}");}
    @ParameterizedTest @ValueSource(strings={"0","-1","5000000001","9223372036854775807","99999999999999999999999","200.0","2e2","\"200\"","null","true","[]","{}"})
    void strictAmounts(String raw){var b=body();b.set("expectedPayableAmount",json.readTree(raw));assertThatThrownBy(()->PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class))).isInstanceOf(BusinessException.class);}
    @ParameterizedTest @ValueSource(strings={"-1","01","+1"," 1","1 ","9223372036854775808","","1.0"})
    void canonicalVersion(String raw){var b=body();b.put("expectedPriceVersion",raw);assertThatThrownBy(()->PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class))).isInstanceOf(BusinessException.class);}
    @ParameterizedTest @ValueSource(strings={"serviceId","fileId","paperSize","colorMode","sideMode","copies","expectedPriceVersion","expectedPayableAmount"})
    void missingOrNullRequired(String field){var b=body();b.remove(field);assertThatThrownBy(()->PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class))).isInstanceOf(BusinessException.class);b.putNull(field);assertThatThrownBy(()->PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class))).isInstanceOf(BusinessException.class);}
    @Test void upperBoundsAndUnicodeNoteNormalizationAreDeterministic(){var b=body();b.put("expectedPriceVersion",Long.toString(Long.MAX_VALUE));b.put("expectedPayableAmount",5000000000L);b.put("note","😀".repeat(300));var r=PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class));assertThat(r.version()).isEqualTo(Long.MAX_VALUE);assertThat(r.amount()).isEqualTo(5000000000L);b.put("note","😀".repeat(301));assertThatThrownBy(()->PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class))).isInstanceOf(BusinessException.class);}
    @Test void emptyAndAbsentNoteShareHashButContentsAndExpectationsMatter(){var b=body();var first=PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class));b.put("note","  ");assertThat(PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class)).hash()).isEqualTo(first.hash());b.putNull("note");assertThat(PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class)).hash()).isEqualTo(first.hash());b.put("note","a\nb");var n=PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class));b.put("note","b\na");assertThat(PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class)).hash()).isNotEqualTo(n.hash());b.put("expectedPriceVersion","1");assertThat(PrintOrderPolicy.parse(json.convertValue(b,PrintCreate.class)).hash()).isNotEqualTo(first.hash());}
}
