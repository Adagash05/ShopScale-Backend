package com.amsati.shopscale.partners;

import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time creation of the FIRST platform administrator account, for a fresh production database where nobody can yet log in.
 *
 * It does nothing unless BOTH PLATFORM_ADMIN_BOOTSTRAP_EMAIL and PLATFORM_ADMIN_BOOTSTRAP_PASSWORD are set, and it:
 *  - only creates the account when that email is already listed in PLATFORM_ADMIN_EMAILS (that list, not this class, is what grants access);
 *  - never runs when any listed administrator already has an account, so it cannot create a second "initial" administrator;
 *  - never touches an existing account (no password reset, no promotion);
 *  - refuses weak passwords, hashes with the normal PasswordEncoder, and never logs the password.
 * After the first start, delete both variables: the program then has nothing to do.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PartnerAccess access;
    private final String email;
    private final String password;
    private final String fullName;

    public PlatformAdminBootstrap(UserRepository users, PasswordEncoder encoder, PartnerAccess access,
                                  @Value("${app.platform-admin.bootstrap-email:}") String email,
                                  @Value("${app.platform-admin.bootstrap-password:}") String password,
                                  @Value("${app.platform-admin.bootstrap-name:Platform Administrator}") String fullName) {
        this.users = users;
        this.encoder = encoder;
        this.access = access;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.fullName = fullName == null || fullName.isBlank() ? "Platform Administrator" : fullName.trim();
    }

    @Override
    public void run(ApplicationArguments args) { provision(); }

    /** Returns true only when an account was created. */
    @Transactional
    public boolean provision() {
        if (email.isEmpty() && password.isEmpty()) return false;
        if (email.isEmpty() || password.isEmpty()) { log.error("Platform admin bootstrap skipped: set both PLATFORM_ADMIN_BOOTSTRAP_EMAIL and PLATFORM_ADMIN_BOOTSTRAP_PASSWORD."); return false; }
        if (!access.adminEmails().contains(email)) { log.error("Platform admin bootstrap skipped: the bootstrap email must also be listed in PLATFORM_ADMIN_EMAILS."); return false; }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255) { log.error("Platform admin bootstrap skipped: the bootstrap email is not a valid address."); return false; }
        String weak = weakness(password);
        if (weak != null) { log.error("Platform admin bootstrap skipped: the password is too weak ({}).", weak); return false; }
        for (String listed : access.adminEmails()) {
            if (users.existsByEmail(listed)) { log.info("Platform admin bootstrap not needed: an administrator account already exists. Remove the PLATFORM_ADMIN_BOOTSTRAP_* variables."); return false; }
        }
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(fullName);
        u.setActive(true);
        u.setEmailVerified(true);   // the operator who controls the server configuration vouches for this address
        users.save(u);
        log.warn("Platform administrator account created. Remove PLATFORM_ADMIN_BOOTSTRAP_EMAIL and PLATFORM_ADMIN_BOOTSTRAP_PASSWORD from the environment now.");
        return true;
    }

    /** Null when the password is acceptable. 72 is the BCrypt limit the rest of the app uses. */
    static String weakness(String p) {
        if (p.length() < 12) return "at least 12 characters";
        if (p.length() > 72) return "at most 72 characters";
        if (!p.matches(".*[a-z].*") || !p.matches(".*[A-Z].*") || !p.matches(".*\\d.*") || !p.matches(".*[^A-Za-z0-9].*")) return "needs lower case, upper case, a digit and a symbol";
        return null;
    }
}
