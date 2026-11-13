package com.campuslife.order.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class PrintQuotePolicyTest {
    @ParameterizedTest
    @CsvSource({"5,2,SINGLE,20,10,200","5,2,DOUBLE,20,6,200","3,2,DOUBLE,20,4,120",
            "1,1,DOUBLE,1,1,1","1,100,DOUBLE,100000,100,10000000",
            "500,100,SINGLE,100000,50000,5000000000","500,100,DOUBLE,100000,25000,5000000000",
            "499,100,DOUBLE,100000,25000,4990000000","2,1,DOUBLE,20,1,40","500,1,SINGLE,1,500,500"})
    void exactAmountsAndPerCopyPaperRounding(int pages,int copies,String side,long unit,long sheets,long amount) {
        var result=PrintQuotePolicy.calculate(pages,copies,side,unit);
        assertThat(result.sheets()).isEqualTo(sheets);assertThat(result.amount()).isEqualTo(amount);
    }
    @ParameterizedTest
    @CsvSource({"0,1,SINGLE,1","501,1,SINGLE,1","1,0,SINGLE,1","1,101,SINGLE,1",
            "1,1,SINGLE,0","1,1,SINGLE,100001","1,1,INVALID,1","500,100,SINGLE,9223372036854775807"})
    void corruptInputsNeverProducePlausibleQuote(int pages,int copies,String side,long unit) {
        assertThatThrownBy(()->PrintQuotePolicy.calculate(pages,copies,side,unit)).isInstanceOf(IllegalStateException.class);
    }
}
