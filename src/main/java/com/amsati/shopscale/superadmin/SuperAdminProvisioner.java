package com.amsati.shopscale.superadmin;

import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time provisioning of the platform owner's Super Admin account. There is no endpoint and no page that can do this.
 *
 * Runs at start-up only when SHOPSCALE_SUPER_ADMIN_EMAIL and SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD are both set; with the command-line
 * option {@code --provision-super-admin} it provisions and then exits (a deployment command). It:
 *  - creates the account only when NO Super Admin exists yet (serialised with a database advisory lock, so two instances cannot both create one);
 *  - never touches an existing user: an email that is already registered is refused, so nobody is promoted and no password is overwritten;
 *  - refuses weak passwords, hashes with the normal BCrypt encoder, never logs the password;
 *  - forces a password change at first sign-in.
 */
@Component
public class SuperAdminProvisioner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SuperAdminProvisioner.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext ctx;
    private final String email, password, name;

    public SuperAdminProvisioner(JdbcTemplate jdbc, PasswordEncoder encoder, TransactionTemplate tx, ConfigurableApplicationContext ctx,
                                 @Value("${app.super-admin.email:}") String email, @Value("${app.super-admin.initial-password:}") String password,
                                 @Value("${app.super-admin.name:Platform Owner}") String name) {
        this.jdbc = jdbc; this.encoder = encoder; this.tx = tx; this.ctx = ctx;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.name = name == null || name.isBlank() ? "Platform Owner" : name.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exitAfter = args.containsOption("provision-super-admin");
        Result r = provision();
        if (exitAfter) {
            log.info("Super Admin provisioning finished: {}", r);
            System.exit(SpringApplication.exit(ctx, () -> r == Result.CREATED || r == Result.ALREADY_EXISTS || r == Result.NOT_CONFIGURED ? 0 : 1));
        }
    }

    public enum Result { NOT_CONFIGURED, CREATED, ALREADY_EXISTS, REFUSED }

    public Result provision() {
        if (email.isEmpty() && password.isEmpty()) return Result.NOT_CONFIGURED;
        if (email.isEmpty() || password.isEmpty()) { log.error("Super Admin provisioning skipped: set both SHOPSCALE_SUPER_ADMIN_EMAIL and SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD."); return Result.REFUSED; }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255) { log.error("Super Admin provisioning skipped: the email address is not valid."); return Result.REFUSED; }
        String weak = weakness(password);
        if (weak != null) { log.error("Super Admin provisioning skipped: the initial password is too weak ({}).", weak); return Result.REFUSED; }
        return tx.execute(st -> {
            jdbc.execute("select pg_advisory_xact_lock(727251)");
            Integer existing = jdbc.queryForObject("select count(*) from users where platform_role = 'SUPER_ADMIN'", Integer.class);
            if (existing != null && existing > 0) { log.info("Super Admin provisioning not needed: a Super Admin already exists. Remove the SHOPSCALE_SUPER_ADMIN_* variables."); return Result.ALREADY_EXISTS; }
            Integer taken = jdbc.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email);
            if (taken != null && taken > 0) { log.error("Super Admin provisioning refused: that email already belongs to a registered account. Use an email that is not registered."); return Result.REFUSED; }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into users (id, email, password_hash, full_name, active, email_verified, platform_role, must_change_password) values (?,?,?,?,true,true,'SUPER_ADMIN',true)",
                id, email, encoder.encode(password), name);
            jdbc.update("insert into admin_audit_log (id, action, target_type, target_id, success, reason) values (?,?,?,?,true,?)",
                UUID.randomUUID(), "SUPER_ADMIN_PROVISIONED", "user", id.toString(), "one-time provisioning");
            log.warn("Super Admin account created. Remove SHOPSCALE_SUPER_ADMIN_EMAIL and SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD from the environment now, and sign in at /super-admin/login (you will be asked to change the password).");
            return Result.CREATED;
        });
    }

    /** Null when acceptable. 72 is the BCrypt input limit. */
    public static String weakness(String p) {
        if (p == null || p.length() < 12) return "at least 12 characters";
        if (p.length() > 72) return "at most 72 characters";
        if (!p.matches(".*[a-z].*") || !p.matches(".*[A-Z].*") || !p.matches(".*\\d.*") || !p.matches(".*[^A-Za-z0-9].*")) return "needs lower case, upper case, a digit and a symbol";
        return null;
    }
}
