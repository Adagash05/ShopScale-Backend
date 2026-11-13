package com.amsati.shopscale.common;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells the operator at startup whether account emails (verification, password reset) can be sent, and why not.
 * A half-set configuration (host without sender, or a host that needs a login without a password) refuses to start in
 * production, because it would silently drop every confirmation email. No mail settings at all only warns: the app still runs
 * and users are told email is not set up. Values are never printed.
 */
@Slf4j
@Component
public class MailConfigDiagnostics implements InitializingBean {
    @Value("${app.env:production}") private String env;
    @Value("${app.mail.from:}") private String from;
    @Value("${spring.mail.host:}") private String host;
    @Value("${spring.mail.port:}") private String port;
    @Value("${spring.mail.username:}") private String username;
    @Value("${spring.mail.password:}") private String password;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(from, host, username, password);
        boolean production = "production".equalsIgnoreCase(env == null ? "production" : env.trim());
        if (host.isBlank() && from.isBlank()) {
            log.warn("Email is NOT configured (SPRING_MAIL_HOST and MAIL_FROM are empty): confirmation and password-reset emails will not be sent.");
        } else if (problems.isEmpty()) {
            log.info("Email configured: host={} port={} username set={} sender set=true", host, port.isBlank() ? "(default)" : port, !username.isBlank());
        } else if (production) {
            throw new IllegalStateException("ShopScale email is only partly configured:\n - " + String.join("\n - ", problems)
                + "\nSet the missing variables, or clear the others to run without email.");
        } else {
            problems.forEach(p -> log.warn("Email configuration: {}", p));
        }
    }

    public static List<String> problems(String from, String host, String username, String password) {
        List<String> out = new ArrayList<>();
        boolean anyHost = host != null && !host.isBlank();
        boolean anyFrom = from != null && !from.isBlank();
        if (!anyHost && !anyFrom) return out;
        if (!anyHost) out.add("SPRING_MAIL_HOST is not set (for Resend: smtp.resend.com)");
        if (!anyFrom) out.add("MAIL_FROM is not set (for example: ShopScale <no-reply@shopscale.com.ng>)");
        else if (!from.matches("^(?:[^<>@]+<[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+>|[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+)$")) out.add("MAIL_FROM is not a valid address; use name@domain or Name <name@domain>");
        boolean hasUser = username != null && !username.isBlank();
        boolean hasPass = password != null && !password.isBlank();
        if (hasUser != hasPass) out.add("SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD must be set together");
        return out;
    }
}
