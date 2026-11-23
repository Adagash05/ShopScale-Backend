package com.amsati.shopscale.invoice;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    Optional<Invoice> findByIdAndBusinessId(UUID id, UUID businessId);

    Optional<Invoice> findByShareToken(String token);

    Optional<Invoice> findByBusinessIdAndClientRequestId(UUID businessId, String clientRequestId);

    /** Serialises identical create requests for one business until the transaction ends, so two taps at once make one invoice. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) t", nativeQuery = true)
    int lockCreateRequest(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.businessId = :b")
    Optional<Invoice> findForUpdate(@Param("id") UUID id, @Param("b") UUID businessId);

    /** status: stored status, or the derived OVERDUE; blank = all. q matches number, customer name or phone. */
    @Query("""
        select i from Invoice i where i.businessId = :b
          and (:customer is null or i.customerId = :customer)
          and (:status = '' or (:status = 'OVERDUE' and i.status in ('SENT','PARTIALLY_PAID') and i.dueDate < :today and i.amountPaid < i.total)
               or (:status <> 'OVERDUE' and i.status = :status))
          and (:q = '' or lower(i.customerName) like concat('%', :q, '%') or lower(coalesce(i.customerPhone, '')) like concat('%', :q, '%')
               or cast(i.invoiceNumber as string) like concat('%', :q, '%'))
        order by i.createdAt desc
        """)
    Page<Invoice> search(@Param("b") UUID businessId, @Param("customer") UUID customer, @Param("status") String status,
                         @Param("q") String q, @Param("today") LocalDate today, Pageable pageable);
}

interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {
    List<InvoiceItem> findByInvoiceIdOrderByPosition(UUID invoiceId);
    void deleteByInvoiceId(UUID invoiceId);
}

interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {
    List<InvoicePayment> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);
    Optional<InvoicePayment> findByInvoiceIdAndClientRequestId(UUID invoiceId, String clientRequestId);
}

interface InvoiceSettingsRepository extends JpaRepository<InvoiceSettings, UUID> {}

interface InvoiceReportRepository extends JpaRepository<Invoice, UUID> {
    /*
     * Money still owed on invoices that are NOT backed by a sale or an order (those are already counted as customer credit / are documents only).
     * Draft and cancelled invoices are not debt. Plain aggregates: sum() is null when nothing matches, the service turns that into zero.
     */
    @Query("select sum(i.total - i.amountPaid) from Invoice i where i.businessId = :b and i.status in ('SENT','PARTIALLY_PAID') and i.amountPaid < i.total and i.sourceSaleId is null and i.sourceDocumentId is null")
    java.math.BigDecimal openAmount(@Param("b") UUID businessId);

    @Query("select count(i) from Invoice i where i.businessId = :b and i.status in ('SENT','PARTIALLY_PAID') and i.amountPaid < i.total and i.sourceSaleId is null and i.sourceDocumentId is null")
    long openCount(@Param("b") UUID businessId);

    @Query("select sum(i.total - i.amountPaid) from Invoice i where i.businessId = :b and i.status in ('SENT','PARTIALLY_PAID') and i.amountPaid < i.total and i.dueDate < :today and i.sourceSaleId is null and i.sourceDocumentId is null")
    java.math.BigDecimal overdueAmount(@Param("b") UUID businessId, @Param("today") LocalDate today);

    @Query("select count(i) from Invoice i where i.businessId = :b and i.status in ('SENT','PARTIALLY_PAID') and i.amountPaid < i.total and i.dueDate < :today and i.sourceSaleId is null and i.sourceDocumentId is null")
    long overdueCount(@Param("b") UUID businessId, @Param("today") LocalDate today);

    /** Payments taken on standalone invoices in a period. A payment on a sale-backed invoice is already a customer repayment, so it is not counted here. */
    @Query("select sum(p.amount) from InvoicePayment p, Invoice i where p.invoiceId = i.id and i.businessId = :b and p.businessId = :b and i.sourceSaleId is null and i.sourceDocumentId is null and p.createdAt >= :from and p.createdAt < :to")
    java.math.BigDecimal standalonePaymentsBetween(@Param("b") UUID businessId, @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
