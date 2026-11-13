package com.amsati.shopscale.team;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place ShopScale sends email (Resend over SMTP through Spring's JavaMailSender).
 *
 * Two ways to send:
 *  - {@link #send}: synchronous, returns whether the provider accepted the message (invitations, which show the link when it did not).
 *  - {@link #sendAfterCommit}: for account emails. Never runs inside the caller's database transaction: it waits for the commit
 *    (so a rolled-back registration sends nothing and no row lock is held while the provider answers), then delivers on a small
 *    worker pool, retrying with back-off. Failures are logged at ERROR with the provider's reason (never the message, token or credentials).
 *
 * Email is sent only if SPRING_MAIL_HOST and MAIL_FROM are both set; otherwise callers are told it was not sent.
 */
@Slf4j
@Service
public class EmailService {
    /** Delivery seam: the real one uses JavaMail; tests substitute their own. */
    @FunctionalInterface
    public interface Delivery { void deliver(String to, String subject, String text, String html) throws Exception; }

    static final long[] DEFAULT_RETRY_SECONDS = {5, 30, 120};

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final Delivery delivery;
    private final ScheduledExecutorService pool;
    private final long[] retrySeconds;

    @Autowired
    public EmailService(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from:}") String from) {
        this.sender = sender;
        this.from = from == null ? "" : from.trim();
        this.delivery = this::deliverWithJavaMail;
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "mail-sender");
            t.setDaemon(true);
            return t;
        });
        p.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.pool = p;
        this.retrySeconds = DEFAULT_RETRY_SECONDS;
    }

    /** For tests. */
    EmailService(Delivery delivery, String from, ScheduledExecutorService pool, long[] retrySeconds) {
        this.sender = null;
        this.from = from;
        this.delivery = delivery;
        this.pool = pool;
        this.retrySeconds = retrySeconds;
    }

    public boolean isConfigured() {
        boolean transport = delivery != null && (sender == null || sender.getIfAvailable() != null);
        return transport && !from.isBlank();
    }

    /** @return true only if the mail server accepted the message. Blocks for the provider's answer; do not call inside a transaction. */
    public boolean send(String to, String subject, String body) {
        if (!isConfigured()) {
            log.info("Email not configured; not sending '{}'", subject);
            return false;
        }
        try {
            delivery.deliver(to, subject, body, null);
            return true;
        } catch (Exception e) {
            log.warn("Email send failed: {}", describe(e));
            return false;
        }
    }

    /**
     * Queues a message to go out once the surrounding transaction commits (immediately when there is none).
     * @return false if email is not configured (nothing queued); true when accepted for delivery.
     */
    public boolean sendAfterCommit(String to, String subject, String text, String html) {
        if (!isConfigured()) {
            log.warn("Email is not configured (SPRING_MAIL_HOST / MAIL_FROM); '{}' to {} was not sent", subject, mask(to));
            return false;
        }
        Runnable queue = () -> enqueue(to, subject, text, html);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queue.run(); }
            });
        } else {
            queue.run();
        }
        return true;
    }

    private void enqueue(String to, String subject, String text, String html) {
        try {
            pool.execute(() -> attempt(to, subject, text, html, 0));
        } catch (RejectedExecutionException e) {
            log.error("Email '{}' to {} could not be queued (the server is shutting down)", subject, mask(to));
        }
    }

    private void attempt(String to, String subject, String text, String html, int n) {
        try {
            delivery.deliver(to, subject, text, html);
            log.info("Email '{}' delivered to the mail provider for {} (attempt {})", subject, mask(to), n + 1);
        } catch (Exception e) {
            if (n < retrySeconds.length) {
                log.warn("Email '{}' to {} failed on attempt {}: {}. Retrying in {}s", subject, mask(to), n + 1, describe(e), retrySeconds[n]);
                try {
                    pool.schedule(() -> attempt(to, subject, text, html, n + 1), retrySeconds[n], TimeUnit.SECONDS);
                } catch (RejectedExecutionException r) {
                    log.error("Email '{}' to {} was not sent: shutting down before retry", subject, mask(to));
                }
            } else {
                log.error("Email '{}' to {} was NOT delivered after {} attempts: {}. The user can request another from the app.",
                    subject, mask(to), n + 1, describe(e));
            }
        }
    }

    private void deliverWithJavaMail(String to, String subject, String text, String html) throws Exception {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) throw new IllegalStateException("Mail sender is not configured");
        MimeMessage m = s.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(m, html != null, "UTF-8");
        h.setFrom(from);
        h.setTo(to);
        h.setSubject(subject);
        if (html != null) h.setText(text, html); else h.setText(text);
        s.send(m);
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Provider's reason without the message body: exception type plus the root cause text (for example a 550 reply from the SMTP server). */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() == null ? "" : root.getMessage().replaceAll("\\s+", " ");
        if (msg.length() > 200) msg = msg.substring(0, 200);
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : " - " + msg);
    }

    static String mask(String email) {
        if (email == null) return "?";
        int at = email.indexOf('@');
        if (at < 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
