package com.amsati.shopscale.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amsati.shopscale.common.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductCsvTest {
    @Test
    void readsQuotedFieldsCrlfAndBom() {
        List<List<String>> rows = ProductCsv.parse("﻿name,category\r\n\"Rice, 50kg\",\"Grains \"\"A\"\"\"\r\nSugar,\r\n");
        assertEquals(3, rows.size());
        assertEquals(List.of("name", "category"), rows.get(0));
        assertEquals(List.of("Rice, 50kg", "Grains \"A\""), rows.get(1));
        assertEquals(List.of("Sugar", ""), rows.get(2));
    }

    @Test
    void ignoresBlankLinesAndAllowsLineBreaksInsideQuotes() {
        List<List<String>> rows = ProductCsv.parse("a,b\n\n\"x\ny\",z");
        assertEquals(2, rows.size());
        assertEquals(List.of("x\ny", "z"), rows.get(1));
    }

    @Test
    void numbersAcceptNairaSignsAndCommasButRejectNegativesAndText() {
        assertEquals(new BigDecimal("1500"), ProductImportService.number("₦1,500", "Price"));
        assertEquals(null, ProductImportService.number(null, "Price"));
        assertThrows(ApiException.class, () -> ProductImportService.number("-5", "Price"));
        assertThrows(ApiException.class, () -> ProductImportService.number("abc", "Price"));
    }
}
