package com.amsati.shopscale.security;

import static org.junit.jupiter.api.Assertions.*;

import com.amsati.shopscale.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {
    static String newKey() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getEncoder().encodeToString(b); }

    @Test void roundTripAndNothingReadableInTheStoredValue() {
        SecretBox box = new SecretBox(newKey());
        String enc = box.encrypt("sk_test_abcdef1234567890");
        assertEquals("sk_test_abcdef1234567890", box.decrypt(enc));
        assertFalse(enc.contains("sk_test"));
        assertNotEquals(enc, box.encrypt("sk_test_abcdef1234567890"));   // random IV
        assertEquals(12 + "sk_test_abcdef1234567890".length() + 16, Base64.getDecoder().decode(enc).length);
    }

    @Test void tamperedOrWrongKeyIsRejected() {
        SecretBox box = new SecretBox(newKey());
        byte[] b = Base64.getDecoder().decode(box.encrypt("secret"));
        b[b.length - 1] ^= 1;
        String bad = Base64.getEncoder().encodeToString(b);
        assertThrows(IllegalStateException.class, () -> box.decrypt(bad));
        assertThrows(IllegalStateException.class, () -> new SecretBox(newKey()).decrypt(box.encrypt("secret")));
    }

    @Test void productionWithoutAKeyRefusesAndWritesNoFile(@TempDir Path dir) {
        Path f = dir.resolve("dev.key");
        SecretBox box = new SecretBox("", "production", f.toString());
        assertFalse(box.isConfigured());
        assertEquals(SecretBox.Source.NONE, box.source());
        assertTrue(box.problem().contains("APP_ENCRYPTION_KEY is not set"));
        assertThrows(ApiException.class, () -> box.encrypt("x"));
        assertFalse(Files.exists(f));
    }

    @Test void invalidKeysAreReportedWithoutLeakingThem(@TempDir Path dir) {
        SecretBox shortKey = new SecretBox(Base64.getEncoder().encodeToString(new byte[16]), "development", dir.resolve("k").toString());
        assertFalse(shortKey.isConfigured());
        assertTrue(shortKey.problem().contains("16 bytes"));
        assertFalse(Files.exists(dir.resolve("k")));   // an invalid configured key never falls back silently
        assertTrue(new SecretBox("%%%", "development", dir.resolve("k").toString()).problem().contains("not valid base64"));
    }

    @Test void developmentKeyFilePersistsAcrossRestarts(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/dev.key");
        SecretBox first = new SecretBox("", "development", f.toString());
        assertTrue(first.isConfigured());
        assertEquals(SecretBox.Source.LOCAL_FILE, first.source());
        String enc = first.encrypt("sk_live_x");
        assertEquals("sk_live_x", new SecretBox("", "development", f.toString()).decrypt(enc));
        assertEquals(32, Base64.getDecoder().decode(Files.readString(f).trim()).length);
    }

    @Test void aCorruptKeyFileIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("corrupt.key");
        Files.writeString(f, "garbage!!");
        assertFalse(new SecretBox("", "development", f.toString()).isConfigured());
        assertEquals("garbage!!", Files.readString(f));
    }

    @Test void environmentKeyTakesPriorityOverTheFile(@TempDir Path dir) {
        String k = newKey();
        SecretBox box = new SecretBox(k, "development", dir.resolve("dev.key").toString());
        assertEquals(SecretBox.Source.ENVIRONMENT, box.source());
        assertFalse(Files.exists(dir.resolve("dev.key")));
    }
}
