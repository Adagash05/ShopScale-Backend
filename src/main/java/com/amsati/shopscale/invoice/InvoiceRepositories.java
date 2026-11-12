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
