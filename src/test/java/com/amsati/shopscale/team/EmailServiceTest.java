package com.amsati.shopscale.team;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EmailServiceTest {
    private final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(2);

    @AfterEach void stop() {
        pool.shutdownNow();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private EmailService svc(EmailService.Delivery d, long... retry) { return new EmailService(d, "ShopScale <no-reply@shopscale.com.ng>", pool, retry); }

    @Test void deliversOnceWhenTheProviderAccepts() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        List<String> to = Collections.synchronizedList(new ArrayList<>());
        EmailService s = svc((t, sub, text, html) -> { to.add(t); sent.countDown(); }, 0, 0);
        assertTrue(s.sendAfterCommit("ada@example.com", "Hi", "text", "<p>html</p>"));
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("ada@example.com"), to);
    }

    @Test void aSlowProviderDoesNotBlockTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EmailService s = svc((t, sub, text, html) -> release.await(5, TimeUnit.SECONDS), 0);
        long start = System.nanoTime();
        assertTrue(s.sendAfterCommit("ada@example.com", "Hi", "t", "h"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500, "the request thread must not wait for SMTP");
        release.countDown();
    }

    @Test void retriesAfterAProviderFailureAndThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch ok = new CountDownLatch(1);
        EmailService s = svc((t, sub, text, html) -> {
            if (calls.incrementAndGet() < 3) throw new java.io.IOException("550 sender domain not verified");
            ok.countDown();
        }, 0, 0, 0);
        s.sendAfterCommit("ada@example.com", "Hi", "t", "h");
        assertTrue(ok.await(3, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
    }

    @Test void givesUpAfterTheLastRetryWithoutThrowingToAnyone() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch last = new CountDownLatch(3);
        EmailService s = svc((t, sub, text, html) -> { calls.incrementAndGet(); last.countDown(); throw new IllegalStateException("provider down"); }, 0, 0);
        assertDoesNotThrow(() -> s.sendAfterCommit("ada@example.com", "Hi", "t", "h"));
        assertTrue(last.await(3, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(3, calls.get(), "first try plus two retries, then stop");
    }

    @Test void waitsForTheCommitAndSendsNothingOnRollback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch sent = new CountDownLatch(1);
        EmailService s = svc((t, sub, text, html) -> { calls.incrementAndGet(); sent.countDown(); }, 0);

        TransactionSynchronizationManager.initSynchronization();
        s.sendAfterCommit("ada@example.com", "Hi", "t", "h");
        Thread.sleep(150);
        assertEquals(0, calls.get(), "nothing is sent while the transaction is still open");
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        TransactionSynchronizationManager.clearSynchronization();

        // Rolled back: afterCommit is never called, so nothing goes out.
        TransactionSynchronizationManager.initSynchronization();
        s.sendAfterCommit("bob@example.com", "Hi", "t", "h");
        TransactionSynchronizationManager.getSynchronizations().forEach(sy -> sy.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        Thread.sleep(150);
        assertEquals(1, calls.get());
    }

    @Test void notConfiguredMeansNotSentAndSaysSo() {
        EmailService s = new EmailService((t, sub, text, html) -> fail("must not send"), "", pool, new long[0]);
        assertFalse(s.isConfigured());
        assertFalse(s.sendAfterCommit("ada@example.com", "Hi", "t", "h"));
        assertFalse(s.send("ada@example.com", "Hi", "t"));
    }

    @Test void synchronousSendReportsFailureInsteadOfThrowing() {
        EmailService s = svc((t, sub, text, html) -> { throw new RuntimeException("boom"); });
        assertFalse(s.send("ada@example.com", "Hi", "t"));
    }

    @Test void logsNeverCarryTheFullAddressOrMessage() {
        assertEquals("a***@example.com", EmailService.mask("ada@example.com"));
        assertEquals("***", EmailService.mask("nonsense"));
        String d = EmailService.describe(new RuntimeException("wrapper", new java.io.IOException("535 Authentication failed")));
        assertTrue(d.contains("535 Authentication failed"));
    }
}
