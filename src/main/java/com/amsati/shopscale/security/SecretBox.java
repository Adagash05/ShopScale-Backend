package com.amsati.shopscale.security;

import com.amsati.shopscale.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for secrets that must be stored (merchant Paystack keys, saved card tokens, payout account numbers).
 *
 * Where the key comes from:
 *  1. APP_ENCRYPTION_KEY: base64 of 32 random bytes (openssl rand -base64 32). Required in production, where the app refuses to start without it.
 *     Keep the same value across restarts and deployments: it is what makes stored credentials readable.
 *  2. Local development only (APP_ENV is not production) and no APP_ENCRYPTION_KEY: a random key is created once and kept in a file
 *     (default ~/.shopscale/dev-encryption.key, owner-only permissions), so credentials saved locally still decrypt after a restart.
 *     Never used in production: a key that lives only on a container disk would silently orphan every stored credential on redeploy.
 *
 * Stored format (unchanged, so anything encrypted before this version still decrypts): base64( 12-byte random IV || ciphertext+16-byte GCM tag ).
 */
@Component
public class SecretBox {
    private static final SecureRandom RNG = new SecureRandom();
    private static final System.Logger LOG = System.getLogger(SecretBox.class.getName());

    /** Where the key in use came from. */
    public enum Source { ENVIRONMENT, LOCAL_FILE, NONE }

    private final byte[] key;
    private final Source source;
    private final String problem;

    @Autowired
    public SecretBox(@Value("${app.encryption-key:}") String base64Key,
                     @Value("${app.env:production}") String env,
                     @Value("${app.encryption-key-file:}") String devKeyFile) {
        byte[] k = null;
        Source src = Source.NONE;
        String why = null;
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                byte[] d = Base64.getDecoder().decode(base64Key.trim());
                if (d.length == 32) { k = d; src = Source.ENVIRONMENT; }
                else why = "APP_ENCRYPTION_KEY decodes to " + d.length + " bytes; it must be exactly 32 (generate one with: openssl rand -base64 32)";
            } catch (IllegalArgumentException e) {
                why = "APP_ENCRYPTION_KEY is not valid base64 (generate one with: openssl rand -base64 32)";
            }
        } else if (!isProduction(env)) {
            try {
                k = loadOrCreateDevKey(devKeyFile);
                src = Source.LOCAL_FILE;
            } catch (IOException | RuntimeException e) {
                why = "APP_ENCRYPTION_KEY is not set and the local development key file could not be used (" + e.getMessage() + ")";
            }
        } else {
            why = "APP_ENCRYPTION_KEY is not set";
        }
        this.key = k;
        this.source = src;
        this.problem = k == null ? why : null;
        if (k == null) LOG.log(System.Logger.Level.ERROR, "Encryption key unavailable: {0}. Saving payment credentials will be refused.", why);
        else if (src == Source.LOCAL_FILE) LOG.log(System.Logger.Level.WARNING, "APP_ENCRYPTION_KEY is not set: using the local development key file. Set APP_ENCRYPTION_KEY for any shared or production environment.");
    }

    /** Key supplied directly (tests and tools). No file fallback. */
    public SecretBox(String base64Key) { this(base64Key, "production", ""); }

    static boolean isProduction(String env) { return env == null || env.isBlank() || "production".equalsIgnoreCase(env.trim()); }

    private static byte[] loadOrCreateDevKey(String file) throws IOException {
        Path p = (file == null || file.isBlank()) ? Path.of(System.getProperty("user.home"), ".shopscale", "dev-encryption.key") : Path.of(file.trim());
        if (Files.exists(p)) {
            byte[] d;
            try { d = Base64.getDecoder().decode(Files.readString(p, StandardCharsets.UTF_8).trim()); }
            catch (IllegalArgumentException e) { throw new IOException("the file exists but is not valid base64; it was not overwritten"); }
            if (d.length != 32) throw new IOException("the file exists but does not hold a 32-byte key; it was not overwritten");
            return d;
        }
        byte[] fresh = new byte[32];
        RNG.nextBytes(fresh);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        try {
            Files.writeString(p, Base64.getEncoder().encodeToString(fresh) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            return loadOrCreateDevKey(file);   // another process created it first: use theirs
        }
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { /* e.g. Windows: the file sits in the user's own profile folder */ }
        return fresh;
    }

    public boolean isConfigured() { return key != null; }

    public Source source() { return source; }

    /** Why there is no key (never contains key material), or null when there is one. */
    public String problem() { return problem; }

    public String encrypt(String plain) {
        if (key == null) throw ApiException.conflict("The server's encryption key is not set up (APP_ENCRYPTION_KEY), so payment keys cannot be saved safely.");
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
        if (key == null) throw ApiException.conflict("The server's encryption key is not set up (APP_ENCRYPTION_KEY).");
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Could not read the stored key. Was APP_ENCRYPTION_KEY changed?", e); }
    }
}
