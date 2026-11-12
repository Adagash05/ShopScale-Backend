package com.amsati.shopscale.invoice;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoicePdfTest {
    @Test
    void rendersAValidPdfWithoutLogoAndSurvivesOddText() {
        var line = new InvoicePdf.Line("Fabric ₦ (special) \\ 中", new BigDecimal("2"), new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000.00"));
        var v = new InvoicePdf.View("Shop", null, null, null, null, "INV-000001", "SENT", LocalDate.of(2026, 11, 10), null, null,
            "Customer", null, null, null, List.of(line), new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000.00"),
            BigDecimal.ZERO, new BigDecimal("2000.00"), null, null);
        byte[] pdf = InvoicePdf.render(v);
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("%PDF-1.4"));
        assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).trim().endsWith("%%EOF"));
    }
}
