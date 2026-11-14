package com.amsati.shopscale.partners;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartnerHelpersTest {
    @Test void selfReferralEmailsAreNormalised() {
        assertEquals("adaobi@gmail.com", AttributionService.normEmail(" Ada.Obi+shop@Gmail.com "));
        assertEquals("adaobi@gmail.com", AttributionService.normEmail("ada.obi@googlemail.com"));
        assertEquals("ada.obi@example.com", AttributionService.normEmail("Ada.Obi+x@Example.com"));   // dots are kept outside Gmail
        assertEquals("nope", AttributionService.normEmail("nope"));
    }

    @Test void customerNamesAreMaskedForPartners() {
        assertEquals("Ada O.", PartnerQueryService.maskName("Ada Chinwe Obi"));
        assertEquals("Ada", PartnerQueryService.maskName("Ada"));
        assertEquals("Customer", PartnerQueryService.maskName("  "));
        assertEquals("Customer", PartnerQueryService.maskName(null));
    }

    @Test void csvCellsAreQuotedAndFormulasNeutralised() {
        assertEquals("", PartnerQueryService.csvCell(null));
        assertEquals("\"a,b\"", PartnerQueryService.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", PartnerQueryService.csvCell("say \"hi\""));
        assertEquals("'=SUM(A1)", PartnerQueryService.csvCell("=SUM(A1)"));
        assertEquals("'@cmd", PartnerQueryService.csvCell("@cmd"));
        assertEquals("-1499.70", PartnerQueryService.csvCell(new BigDecimal("-1499.70")));   // real numbers stay numbers
    }

    @Test void csvHasHeaderAndRows() {
        String out = PartnerQueryService.csv(List.of(Map.of("a", "x", "b", 1)), List.of("a", "b"));
        assertEquals("a,b\r\nx,1\r\n", out);
    }
}
