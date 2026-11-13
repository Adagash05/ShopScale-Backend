package com.amsati.shopscale.team;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmailTemplatesTest {
    private static final String LINK = "https://app.shopscale.com.ng/verify-email?token=abc_DEF-123";

    @Test void verificationEmailCarriesTheLinkInBothPartsAndIsBranded() {
        var m = EmailTemplates.verification("Ada Obi", LINK);
        assertEquals("Confirm your ShopScale email", m.subject());
        assertTrue(m.text().contains(LINK));
        assertTrue(m.html().contains("href=\"" + LINK + "\""));
        assertTrue(m.html().contains("ShopScale"));
        assertTrue(m.text().contains("3 days"));
    }

    @Test void resetEmailSaysOneHourAndLeavesThePasswordAlone() {
        var m = EmailTemplates.passwordReset("Ada", "https://app.shopscale.com.ng/reset-password?token=t");
        assertTrue(m.text().contains("one hour"));
        assertTrue(m.html().contains("reset-password?token=t"));
    }

    @Test void namesCannotInjectMarkupOrExtraLines() {
        var m = EmailTemplates.verification("<script>alert(1)</script>\r\nBcc: x@y.z", LINK);
        assertFalse(m.html().contains("<script>"));
        assertTrue(m.html().contains("&lt;script&gt;"));
        assertFalse(m.text().contains("\r"));
        assertFalse(m.text().split("\n")[0].contains("\n"));
    }
}
