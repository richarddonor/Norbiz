package com.chardizard.Norbiz.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListTotalsTest {

    private record Row(BigDecimal amount, BigDecimal cost) {
    }

    @Test
    void sumsEachColumnSkippingNullsAndLeavesOutAllNullColumns() {
        List<Row> rows = List.of(
                new Row(new BigDecimal("10.50"), null),
                new Row(null, null),
                new Row(new BigDecimal("2.25"), null));

        Map<String, BigDecimal> totals = ListTotals.sum(rows,
                ListTotals.column("amount", Row::amount),
                ListTotals.column("cost", Row::cost));

        assertThat(totals).containsOnlyKeys("amount");
        assertThat(totals.get("amount")).isEqualByComparingTo("12.75");
    }

    @Test
    void noRowsMeansNoTotals() {
        assertThat(ListTotals.sum(List.<Row>of(), ListTotals.column("amount", Row::amount))).isEmpty();
    }
}
