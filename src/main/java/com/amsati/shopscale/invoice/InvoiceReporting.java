package com.amsati.shopscale.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Invoice figures for the dashboard and Ask ShopScale, kept apart from sales so nothing is counted twice:
 *  - an invoice made from a sale adds nothing here (the sale is already counted as revenue and, if unpaid, as customer credit);
 *  - an invoice made from an order is only a document;
 *  - a standalone invoice is "invoiced" money: it is debt once sent, and cash only when a payment is recorded on it.
 */
@Service
@RequiredArgsConstructor
public class InvoiceReporting {
    private final InvoiceReportRepository repo;

    public record Open(BigDecimal outstanding, long count, BigDecimal overdue, long overdueCount) {}

    public Open open(UUID businessId, LocalDate today) {
        return new Open(nz(repo.openAmount(businessId)), repo.openCount(businessId), nz(repo.overdueAmount(businessId, today)), repo.overdueCount(businessId, today));
    }

    /** Payments recorded on standalone invoices between start (inclusive) and end (exclusive). */
    public BigDecimal paymentsReceived(UUID businessId, Instant start, Instant end) {
        return nz(repo.standalonePaymentsBetween(businessId, start, end));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
