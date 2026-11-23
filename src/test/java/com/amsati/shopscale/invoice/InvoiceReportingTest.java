package com.amsati.shopscale.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceReportingTest {
    InvoiceReportRepository repo = mock(InvoiceReportRepository.class);
    InvoiceReporting reporting = new InvoiceReporting(repo);
    UUID bid = UUID.randomUUID();
    LocalDate today = LocalDate.of(2026, 11, 23);

    @Test void noInvoicesGivesZerosNotNulls() {
        // SQL sum() is null when nothing matches: that must never reach the dashboard as a null.
        when(repo.openAmount(any())).thenReturn(null);
        when(repo.overdueAmount(any(), any())).thenReturn(null);
        var o = reporting.open(bid, today);
        assertEquals(0, o.count());
        assertEquals(0, o.outstanding().compareTo(BigDecimal.ZERO));
        assertEquals(0, o.overdue().compareTo(BigDecimal.ZERO));
        assertEquals(0, o.overdueCount());
    }

    @Test void readsOutstandingAndOverdue() {
        when(repo.openAmount(bid)).thenReturn(new BigDecimal("15000.00"));
        when(repo.openCount(bid)).thenReturn(3L);
        when(repo.overdueAmount(bid, today)).thenReturn(new BigDecimal("5000.00"));
        when(repo.overdueCount(bid, today)).thenReturn(1L);
        var o = reporting.open(bid, today);
        assertEquals(0, o.outstanding().compareTo(new BigDecimal("15000")));
        assertEquals(3, o.count());
        assertEquals(0, o.overdue().compareTo(new BigDecimal("5000")));
        assertEquals(1, o.overdueCount());
    }

    @Test void paymentsReceivedTreatsNullAsZero() {
        when(repo.standalonePaymentsBetween(any(), any(), any())).thenReturn(null);
        assertEquals(0, reporting.paymentsReceived(bid, Instant.EPOCH, Instant.now()).compareTo(BigDecimal.ZERO));
    }
}
