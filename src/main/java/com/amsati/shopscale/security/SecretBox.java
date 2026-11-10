package com.amsati.shopscale.security;

import com.amsati.shopscale.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM for secrets that must be stored (merchant Paystack keys). The key comes from APP_ENCRYPTION_KEY (base64, 32 bytes). */
@Component
public class SecretBox {
    private static final SecureRandom RNG = new SecureRandom();
    private final byte[] key;

    public SecretBox(@Value("${app.encryption-key:}") String base64Key) {
        byte[] k = null;
        try { if (base64Key != null && !base64Key.isBlank()) k = Base64.getDecoder().decode(base64Key.trim()); } catch (IllegalArgumentException ignored) { /* reported by isConfigured */ }
        this.key = k != null && k.length == 32 ? k : null;
    }

    public boolean isConfigured() { return key != null; }

    public String encrypt(String plain) {
        if (key == null) throw ApiException.conflict("The server has no encryption key set (APP_ENCRYPTION_KEY), so payment keys cannot be saved safely.");
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new IllegalStateException("Encryption failed", e); }
    }

    public String decrypt(String stored) {
        if (key == null) throw ApiException.conflict("The server has no encryption key set (APP_ENCRYPTION_KEY).");
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Could not read the stored key. Was APP_ENCRYPTION_KEY changed?", e); }
    }
}
