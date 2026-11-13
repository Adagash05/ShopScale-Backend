package com.amsati.shopscale.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MailConfigDiagnosticsTest {
    @Test void noMailSettingsAtAllIsAllowed() {
        assertTrue(MailConfigDiagnostics.problems("", "", "", "").isEmpty());
        assertTrue(MailConfigDiagnostics.problems(null, null, null, null).isEmpty());
    }

    @Test void theResendSetupIsAccepted() {
        assertTrue(MailConfigDiagnostics.problems("ShopScale <no-reply@shopscale.com.ng>", "smtp.resend.com", "resend", "re_xxx").isEmpty());
        assertTrue(MailConfigDiagnostics.problems("no-reply@shopscale.com.ng", "smtp.resend.com", "resend", "re_xxx").isEmpty());
    }

    @Test void halfConfiguredMailIsReportedByVariableName() {
        assertTrue(MailConfigDiagnostics.problems("", "smtp.resend.com", "resend", "k").get(0).contains("MAIL_FROM"));
        assertTrue(MailConfigDiagnostics.problems("a@b.co", "", "resend", "k").get(0).contains("SPRING_MAIL_HOST"));
        assertTrue(MailConfigDiagnostics.problems("a@b.co", "smtp.resend.com", "resend", "").get(0).contains("together"));
    }

    @Test void aMalformedSenderIsRejectedAndNoValueIsEchoed() {
        var p = MailConfigDiagnostics.problems("not an address", "smtp.resend.com", "resend", "secret-key");
        assertEquals(1, p.size());
        assertFalse(p.get(0).contains("secret-key"));
    }
}
