package com.amsati.shopscale.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionConfigValidatorTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String JWT = "x".repeat(40);

    private static List<String> check(String env, String fe, String api, String cors, String ps, String enc, String jwt, boolean secure) {
        return ProductionConfigValidator.problems(env, fe, api, cors, ps, enc, jwt, secure);
    }

    @Test void goodProductionConfigPasses() {
        assertTrue(check("production", "https://app.shopscale.com.ng", "https://api.shopscale.com.ng", "https://app.shopscale.com.ng,https://shopscale.com.ng", "sk_live_x", KEY, JWT, true).isEmpty());
    }
    @Test void developmentAcceptsAnything() { assertTrue(check("development", "http://localhost:5173", "", "", "", "", "", false).isEmpty()); }
    @Test void localhostAndMissingValuesAreRefusedInProduction() {
        List<String> p = check("production", "http://localhost:5173", "", "http://localhost:5173", "", "", "short", false);
        assertEquals(7, p.size());
    }
    @Test void emptyEnvMeansProduction() { assertFalse(check(null, "", "", "", "", "", "", true).isEmpty()); }
    @Test void wrongEncryptionKeyLengthRefused() {
        assertFalse(check("production", "https://a.com.ng", "https://b.com.ng", "https://a.com.ng", "sk_test_x", Base64.getEncoder().encodeToString(new byte[16]), JWT, true).isEmpty());
    }
    @Test void messagesNeverContainSecretValues() {
        String secret = "sk_live_SUPERSECRETVALUE";
        assertTrue(check("production", "http://localhost", "", "", secret.replace("sk_", "pk_"), "", JWT, true).stream().noneMatch(m -> m.contains("SUPERSECRETVALUE")));
    }
}
