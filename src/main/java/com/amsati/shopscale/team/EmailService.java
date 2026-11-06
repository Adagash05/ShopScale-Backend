package com.amsati.shopscale.team;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends email only when SMTP is configured (spring.mail.host / MAIL_HOST). Otherwise it reports false, and callers
 * show the invitation link to the inviter instead of pretending an email went out.
 */
@Slf4j
@Service
public class EmailService {
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public EmailService(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from:}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public boolean isConfigured() { return sender.getIfAvailable() != null && !from.isBlank(); }

    /** @return true only if the mail server accepted the message. */
    public boolean send(String to, String subject, String body) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null || from.isBlank()) {
            log.info("Email not configured; not sending '{}'", subject);
            return false;
        }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            s.send(m);
            return true;
        } catch (Exception e) {
            log.warn("Email send failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
