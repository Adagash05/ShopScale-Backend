package com.amsati.shopscale.invoice;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.CounterService;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerPayment;
import com.amsati.shopscale.customer.CustomerPaymentRepository;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.finance.FinanceService;
import com.amsati.shopscale.sales.*;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer invoices. Rules that keep the books honest:
 *  - Every amount is computed here (InvoiceMath); client totals are never read.
 *  - An invoice made from a SALE starts with what the sale already collected. That money is already in the ledger, so
 *    it is NOT posted again. Only payments recorded on the invoice afterwards post to the ledger, once each, and (for a
 *    sale on credit) reduce what the customer owes, exactly like a customer repayment.
 *  - A standalone invoice posts to the ledger only when a payment is recorded; its balance lives on the invoice and
 *    does not change the customer's credit balance.
 *  - An invoice made from an ORDER is a document only: payments are taken by converting the order to a sale.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {
    private final InvoiceRepository invoices;
    private final InvoiceItemRepository items;
    private final InvoicePaymentRepository payments;
    private final InvoiceSettingsRepository settings;
    private final CustomerRepository customers;
    private final CustomerPaymentRepository customerPayments;
    private final SaleRepository sales;
    private final SaleItemRepository saleItems;
    private final SalePaymentRepository salePayments;
    private final SalesDocumentRepository documents;
    private final SalesDocumentItemRepository documentItems;
    private final BusinessRepository businesses;
    private final CounterService counters;
    private final FinanceService finance;
    private final AuditService audit;

    @Value("${app.public-api-url:}") private String publicApiUrl;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_LOGO_BYTES = 300_000;

    // ---------- requests / responses ----------
    public record LineRequest(@NotBlank @Size(max = 300) String description,
                              @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                              @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice,
                              @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal discount,
                              @DecimalMin("0") @DecimalMax("100") BigDecimal taxRate) {}

    public record InvoiceRequest(UUID customerId, @Size(max = 150) String customerName, @Size(max = 40) String customerPhone,
                                 @Size(max = 255) String customerEmail, @Size(max = 400) String customerAddress,
                                 LocalDate issueDate, LocalDate dueDate, @Size(max = 200) String paymentTerms,
                                 @Size(max = 1000) String note, @Size(max = 1000) String paymentInstructions,
                                 @DecimalMin("0") BigDecimal invoiceDiscount,
                                 UUID sourceSaleId, UUID sourceDocumentId, @Valid @Size(max = 200) List<LineRequest> items) {}

    public record PaymentRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @NotNull PaymentMethod method, @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}

    public record SettingsDto(String paymentInstructions, String defaultTerms, BigDecimal defaultTaxRate, boolean hasLogo) {}
    public record SettingsRequest(@Size(max = 1000) String paymentInstructions, @Size(max = 200) String defaultTerms,
                                  @DecimalMin("0") @DecimalMax("100") BigDecimal defaultTaxRate) {}

    public record LineDto(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate, BigDecimal amount) {}
    public record PaymentDto(UUID id, BigDecimal amount, String method, String note, Instant createdAt) {}
    public record InvoiceSummary(UUID id, String number, String customerName, String status, LocalDate issueDate, LocalDate dueDate,
                                 BigDecimal total, BigDecimal amountPaid, BigDecimal balance) {}
    public record InvoiceDto(UUID id, String number, UUID customerId, String customerName, String customerPhone, String customerEmail, String customerAddress,
                             String status, LocalDate issueDate, LocalDate dueDate, String paymentTerms, BigDecimal subtotal, BigDecimal tax,
                             BigDecimal invoiceDiscount, BigDecimal total, BigDecimal amountPaid, BigDecimal balance, String note, String paymentInstructions,
                             UUID sourceSaleId, UUID sourceDocumentId, boolean canRecordPayments, boolean editable, String shareUrl,
                             Instant sentAt, List<LineDto> items, List<PaymentDto> payments) {}

    private LocalDate today(UUID businessId) {
        Business b = businesses.findById(businessId).orElseThrow();
        return LocalDate.now(ZoneId.of(b.getTimezone() == null ? "Africa/Lagos" : b.getTimezone()));
    }

    private String shareUrl(Invoice i) {
        String base = publicApiUrl == null ? "" : publicApiUrl.replaceAll("/+$", "");
        return base + "/api/public/invoices/" + i.getShareToken() + "/pdf";
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---------- create / update ----------
    @Transactional
    public InvoiceDto create(InvoiceRequest r) {
        UUID bid = Tenant.businessId();
        Invoice inv = new Invoice();
        inv.setBusinessId(bid);
        inv.setCreatedBy(Tenant.userId());
        inv.setShareToken(newToken());
        inv.setInvoiceNumber(counters.next(bid, "invoice"));
        LocalDate today = today(bid);
        inv.setIssueDate(r.issueDate() == null ? today : r.issueDate());
        List<InvoiceItem> lines = new ArrayList<>();
        InvoiceSettings st = settings.findById(bid).orElse(null);

        if (r.sourceSaleId() != null) {
            Sale sale = sales.findByIdAndBusinessId(r.sourceSaleId(), bid).orElseThrow(() -> ApiException.notFound("Sale not found"));
            if (sale.getStatus() != SaleStatus.COMPLETED) throw ApiException.conflict("Only a completed sale can be invoiced");
            fromSale(inv, sale, lines);
        } else if (r.sourceDocumentId() != null) {
            SalesDocument doc = documents.findByIdAndBusinessId(r.sourceDocumentId(), bid).orElseThrow(() -> ApiException.notFound("Order not found"));
            if (doc.getSaleId() != null) throw ApiException.conflict("This order was already turned into a sale. Invoice the sale instead.");
            fromDocument(inv, doc, lines);
        } else {
            applyCustomer(inv, r, bid);
            applyStandalone(inv, r, lines);
        }
        inv.setDueDate(r.dueDate());
        inv.setPaymentTerms(blankToNull(r.paymentTerms() != null ? r.paymentTerms() : st == null ? null : st.getDefaultTerms()));
        inv.setNote(blankToNull(r.note()));
        inv.setPaymentInstructions(blankToNull(r.paymentInstructions() != null ? r.paymentInstructions() : st == null ? null : st.getPaymentInstructions()));
        if (inv.getDueDate() != null && inv.getDueDate().isBefore(inv.getIssueDate())) throw ApiException.badRequest("The due date is before the issue date");
        if (inv.getAmountPaid().compareTo(inv.getTotal()) >= 0 && inv.getTotal().signum() > 0) inv.setStatus("PAID");
        invoices.save(inv);
        saveLines(inv, lines);
        audit.record(bid, Tenant.userId(), "INVOICE_CREATED", "invoice", inv.getId(), InvoiceMath.number(inv.getInvoiceNumber()));
        return dto(inv);
    }

    @Transactional
    public InvoiceDto update(UUID id, InvoiceRequest r) {
        UUID bid = Tenant.businessId();
        Invoice inv = invoices.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Invoice not found"));
        if (!"DRAFT".equals(inv.getStatus())) throw ApiException.conflict("Only a draft can be edited. Cancel this invoice and create a new one.");
        if (inv.getSourceSaleId() == null && inv.getSourceDocumentId() == null) {
            applyCustomer(inv, r, bid);
            List<InvoiceItem> lines = new ArrayList<>();
            applyStandalone(inv, r, lines);
            items.deleteByInvoiceId(inv.getId());
            items.flush();
            saveLines(inv, lines);
        }
        if (r.issueDate() != null) inv.setIssueDate(r.issueDate());
        inv.setDueDate(r.dueDate());
        inv.setPaymentTerms(blankToNull(r.paymentTerms()));
        inv.setNote(blankToNull(r.note()));
        inv.setPaymentInstructions(blankToNull(r.paymentInstructions()));
        if (inv.getDueDate() != null && inv.getDueDate().isBefore(inv.getIssueDate())) throw ApiException.badRequest("The due date is before the issue date");
        inv.setUpdatedAt(Instant.now());
        audit.record(bid, Tenant.userId(), "INVOICE_UPDATED", "invoice", inv.getId(), InvoiceMath.number(inv.getInvoiceNumber()));
        return dto(inv);
    }

    private void applyCustomer(Invoice inv, InvoiceRequest r, UUID bid) {
        if (r.customerId() != null) {
            Customer c = customers.findByIdAndBusinessId(r.customerId(), bid).orElseThrow(() -> ApiException.badRequest("Customer not found"));
            inv.setCustomerId(c.getId());
            inv.setCustomerName(c.getName());
            inv.setCustomerPhone(r.customerPhone() != null && !r.customerPhone().isBlank() ? r.customerPhone().trim() : c.getPhone());
            inv.setCustomerEmail(r.customerEmail() != null && !r.customerEmail().isBlank() ? r.customerEmail().trim() : c.getEmail());
            inv.setCustomerAddress(r.customerAddress() != null && !r.customerAddress().isBlank() ? r.customerAddress().trim() : c.getAddress());
        } else {
            if (r.customerName() == null || r.customerName().isBlank()) throw ApiException.badRequest("Choose a customer or enter a name");
            inv.setCustomerId(null);
            inv.setCustomerName(r.customerName().trim());
            inv.setCustomerPhone(blankToNull(r.customerPhone()));
            inv.setCustomerEmail(blankToNull(r.customerEmail()));
            inv.setCustomerAddress(blankToNull(r.customerAddress()));
        }
    }

    private void applyStandalone(Invoice inv, InvoiceRequest r, List<InvoiceItem> lines) {
        if (r.items() == null || r.items().isEmpty()) throw ApiException.badRequest("Add at least one item");
        List<InvoiceMath.LineIn> in = r.items().stream().map(l -> new InvoiceMath.LineIn(l.quantity(), l.unitPrice(), l.discount(), l.taxRate())).toList();
        InvoiceMath.Totals t;
        try { t = InvoiceMath.compute(in, r.invoiceDiscount()); } catch (IllegalArgumentException e) { throw ApiException.badRequest(e.getMessage()); }
        for (int k = 0; k < r.items().size(); k++) {
            LineRequest l = r.items().get(k);
            InvoiceItem it = new InvoiceItem();
            it.setPosition(k);
            it.setDescription(l.description().trim());
            it.setQuantity(l.quantity());
            it.setUnitPrice(InvoiceMath.money(l.unitPrice()));
            it.setDiscount(InvoiceMath.money(l.discount()));
            it.setTaxRate(l.taxRate() == null ? BigDecimal.ZERO : l.taxRate());
            it.setLineTotal(t.lines().get(k).net());
            it.setTaxAmount(t.lines().get(k).tax());
            lines.add(it);
        }
        setTotals(inv, t);
        inv.setAmountPaid(BigDecimal.ZERO);
    }

    private void setTotals(Invoice inv, InvoiceMath.Totals t) {
        inv.setSubtotal(t.subtotal());
        inv.setLineDiscounts(t.lineDiscounts());
        inv.setTaxTotal(t.tax());
        inv.setInvoiceDiscount(t.invoiceDiscount());
        inv.setTotal(t.total());
    }

    private void fromSale(Invoice inv, Sale sale, List<InvoiceItem> lines) {
        UUID bid = inv.getBusinessId();
        if (sale.getCustomerId() != null) {
            Customer c = customers.findByIdAndBusinessId(sale.getCustomerId(), bid).orElse(null);
            if (c != null) {
                inv.setCustomerId(c.getId()); inv.setCustomerName(c.getName()); inv.setCustomerPhone(c.getPhone());
                inv.setCustomerEmail(c.getEmail()); inv.setCustomerAddress(c.getAddress());
            }
        }
        if (inv.getCustomerName() == null) inv.setCustomerName("Walk-in customer");
        List<InvoiceMath.LineIn> in = new ArrayList<>();
        int pos = 0;
        for (SaleItem si : saleItems.findBySaleId(sale.getId())) {
            String d = si.getProductName() + (si.getVariantLabel() == null || si.getVariantLabel().isBlank() ? "" : " (" + si.getVariantLabel() + ")");
            addSaleLine(lines, in, pos++, d, si.getQuantity(), si.getUnitPrice());
        }
        if (sale.getSurcharge() != null && sale.getSurcharge().signum() > 0) addSaleLine(lines, in, pos, "Service charge", BigDecimal.ONE, sale.getSurcharge());
        InvoiceMath.Totals t = InvoiceMath.compute(in, sale.getDiscount());
        if (t.total().compareTo(InvoiceMath.money(sale.getTotal())) != 0)
            throw ApiException.conflict("This sale's amounts cannot be turned into an invoice automatically. Create the invoice by hand.");
        for (int k = 0; k < lines.size(); k++) { lines.get(k).setLineTotal(t.lines().get(k).net()); lines.get(k).setTaxAmount(t.lines().get(k).tax()); }
        setTotals(inv, t);
        inv.setAmountPaid(InvoiceMath.money(sale.getAmountPaid()));   // already in the ledger: no new posting
        inv.setSourceSaleId(sale.getId());
    }

    private void addSaleLine(List<InvoiceItem> lines, List<InvoiceMath.LineIn> in, int pos, String d, BigDecimal q, BigDecimal price) {
        InvoiceItem it = new InvoiceItem();
        it.setPosition(pos); it.setDescription(d.length() > 300 ? d.substring(0, 300) : d); it.setQuantity(q); it.setUnitPrice(InvoiceMath.money(price));
        lines.add(it);
        in.add(new InvoiceMath.LineIn(q, price, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private void fromDocument(Invoice inv, SalesDocument doc, List<InvoiceItem> lines) {
        UUID bid = inv.getBusinessId();
        if (doc.getCustomerId() != null) {
            customers.findByIdAndBusinessId(doc.getCustomerId(), bid).ifPresent(c -> {
                inv.setCustomerId(c.getId()); inv.setCustomerName(c.getName()); inv.setCustomerPhone(c.getPhone());
                inv.setCustomerEmail(c.getEmail()); inv.setCustomerAddress(c.getAddress());
            });
        }
        if (inv.getCustomerName() == null) inv.setCustomerName("Customer");
        List<InvoiceMath.LineIn> in = new ArrayList<>();
        int pos = 0;
        for (SalesDocumentItem di : documentItems.findByDocumentId(doc.getId())) {
            String d = di.getProductName() + (di.getVariantLabel() == null || di.getVariantLabel().isBlank() ? "" : " (" + di.getVariantLabel() + ")");
            addSaleLine(lines, in, pos++, d, di.getQuantity(), di.getUnitPrice());
        }
        InvoiceMath.Totals t = InvoiceMath.compute(in, doc.getDiscount());
        if (t.total().compareTo(InvoiceMath.money(doc.getTotal())) != 0)
            throw ApiException.conflict("This order's amounts cannot be turned into an invoice automatically. Create the invoice by hand.");
        for (int k = 0; k < lines.size(); k++) { lines.get(k).setLineTotal(t.lines().get(k).net()); lines.get(k).setTaxAmount(t.lines().get(k).tax()); }
        setTotals(inv, t);
        inv.setAmountPaid(InvoiceMath.money(doc.getDepositAmount()));   // the deposit is already in the ledger
        inv.setSourceDocumentId(doc.getId());
    }

    private void saveLines(Invoice inv, List<InvoiceItem> lines) {
        for (InvoiceItem it : lines) { it.setInvoiceId(inv.getId()); it.setBusinessId(inv.getBusinessId()); }
        items.saveAll(lines);
    }

    // ---------- status ----------
    @Transactional
    public InvoiceDto markSent(UUID id) {
        Invoice inv = invoices.findForUpdate(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Invoice not found"));
        if ("DRAFT".equals(inv.getStatus())) {
            inv.setStatus("SENT");
            inv.setSentAt(Instant.now());
            inv.setUpdatedAt(Instant.now());
            audit.record(inv.getBusinessId(), Tenant.userId(), "INVOICE_SENT", "invoice", inv.getId(), InvoiceMath.number(inv.getInvoiceNumber()));
        } else if ("CANCELLED".equals(inv.getStatus())) throw ApiException.conflict("This invoice was cancelled");
        return dto(inv);
    }

    @Transactional
    public InvoiceDto cancel(UUID id) {
        Invoice inv = invoices.findForUpdate(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Invoice not found"));
        if ("CANCELLED".equals(inv.getStatus())) return dto(inv);
        boolean hasInvoicePayments = !payments.findByInvoiceIdOrderByCreatedAtDesc(inv.getId()).isEmpty();
        if (hasInvoicePayments) throw ApiException.conflict("Payments were recorded on this invoice, so it cannot be cancelled");
        if ("PAID".equals(inv.getStatus()) && inv.getSourceSaleId() == null && inv.getSourceDocumentId() == null) throw ApiException.conflict("A paid invoice cannot be cancelled");
        inv.setStatus("CANCELLED");
        inv.setCancelledAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        audit.record(inv.getBusinessId(), Tenant.userId(), "INVOICE_CANCELLED", "invoice", inv.getId(), InvoiceMath.number(inv.getInvoiceNumber()));
        return dto(inv);
    }

    // ---------- payments ----------
    @Transactional
    public InvoiceDto recordPayment(UUID id, PaymentRequest r) {
        UUID bid = Tenant.businessId();
        Invoice inv = invoices.findForUpdate(id, bid).orElseThrow(() -> ApiException.notFound("Invoice not found"));
        if (r.clientRequestId() != null && !r.clientRequestId().isBlank()
            && payments.findByInvoiceIdAndClientRequestId(inv.getId(), r.clientRequestId()).isPresent()) return dto(inv);   // a retry: already recorded
        if ("CANCELLED".equals(inv.getStatus())) throw ApiException.conflict("This invoice was cancelled");
        if (inv.getSourceDocumentId() != null) throw ApiException.conflict("Payments for an order are taken by turning the order into a sale");
        BigDecimal amount = InvoiceMath.money(r.amount());
        BigDecimal outstanding = inv.getTotal().subtract(inv.getAmountPaid());
        if (outstanding.signum() <= 0) throw ApiException.conflict("This invoice is already fully paid");
        if (amount.compareTo(outstanding) > 0) throw ApiException.badRequest("That is more than the " + outstanding.toPlainString() + " still owed on this invoice");
        InvoicePayment p = new InvoicePayment();
        p.setInvoiceId(inv.getId());
        p.setBusinessId(bid);
        p.setAmount(amount);
        p.setMethod(r.method().name());
        p.setNote(blankToNull(r.note()));
        p.setClientRequestId(blankToNull(r.clientRequestId()));
        p.setCreatedBy(Tenant.userId());
        payments.save(p);
        inv.setAmountPaid(inv.getAmountPaid().add(amount));
        inv.setStatus(InvoiceMath.statusAfterPayment(inv.getStatus(), inv.getTotal(), inv.getAmountPaid()));
        inv.setUpdatedAt(Instant.now());
        String ref = InvoiceMath.number(inv.getInvoiceNumber());
        finance.record(bid, r.method(), true, amount, "INVOICE_PAYMENT", "INVOICE", inv.getId(), "Payment for " + ref, Tenant.userId(), null);
        if (inv.getSourceSaleId() != null && inv.getCustomerId() != null) {
            // The sale was on credit: this is the customer paying it off, so their balance falls by the same amount.
            customers.findForUpdate(inv.getCustomerId(), bid).ifPresent(c -> {
                BigDecimal nb = c.getBalance().subtract(amount);
                c.setBalance(nb.signum() < 0 ? BigDecimal.ZERO : nb);
                c.setUpdatedAt(Instant.now());
                CustomerPayment cp = new CustomerPayment();
                cp.setBusinessId(bid); cp.setCustomerId(c.getId()); cp.setAmount(amount); cp.setMethod(r.method());
                cp.setNote("Invoice " + ref); cp.setCreatedBy(Tenant.userId());
                customerPayments.save(cp);   // history only: the money was posted once, above
            });
        }
        audit.record(bid, Tenant.userId(), "INVOICE_PAYMENT", "invoice", inv.getId(), ref + " " + amount.toPlainString());
        return dto(inv);
    }

    // ---------- reads ----------
    @Transactional(readOnly = true)
    public InvoiceDto get(UUID id) { return dto(find(id)); }

    private Invoice find(UUID id) { return invoices.findByIdAndBusinessId(id, Tenant.businessId()).orElseThrow(() -> ApiException.notFound("Invoice not found")); }

    @Transactional(readOnly = true)
    public Page<InvoiceSummary> list(String status, String q, UUID customerId, int page, int size) {
        UUID bid = Tenant.businessId();
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!s.isEmpty() && !List.of("DRAFT", "SENT", "PARTIALLY_PAID", "PAID", "OVERDUE", "CANCELLED").contains(s)) throw ApiException.badRequest("Unknown status");
        LocalDate today = today(bid);
        return invoices.search(bid, customerId, s, q == null ? "" : q.trim().toLowerCase().replace("inv-", "").replaceFirst("^0+(?=.)", ""), today,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
            .map(i -> new InvoiceSummary(i.getId(), InvoiceMath.number(i.getInvoiceNumber()), i.getCustomerName(),
                InvoiceMath.displayStatus(i.getStatus(), i.getTotal(), i.getAmountPaid(), i.getDueDate(), today), i.getIssueDate(), i.getDueDate(),
                i.getTotal(), i.getAmountPaid(), i.getTotal().subtract(i.getAmountPaid())));
    }

    private InvoiceDto dto(Invoice i) {
        LocalDate today = today(i.getBusinessId());
        List<LineDto> ls = items.findByInvoiceIdOrderByPosition(i.getId()).stream()
            .map(x -> new LineDto(x.getDescription(), x.getQuantity(), x.getUnitPrice(), x.getDiscount(), x.getTaxRate(), x.getLineTotal())).toList();
        List<PaymentDto> ps = payments.findByInvoiceIdOrderByCreatedAtDesc(i.getId()).stream()
            .map(x -> new PaymentDto(x.getId(), x.getAmount(), x.getMethod(), x.getNote(), x.getCreatedAt())).toList();
        BigDecimal balance = i.getTotal().subtract(i.getAmountPaid());
        boolean open = !"CANCELLED".equals(i.getStatus()) && balance.signum() > 0 && i.getSourceDocumentId() == null;
        return new InvoiceDto(i.getId(), InvoiceMath.number(i.getInvoiceNumber()), i.getCustomerId(), i.getCustomerName(), i.getCustomerPhone(), i.getCustomerEmail(),
            i.getCustomerAddress(), InvoiceMath.displayStatus(i.getStatus(), i.getTotal(), i.getAmountPaid(), i.getDueDate(), today), i.getIssueDate(), i.getDueDate(),
            i.getPaymentTerms(), i.getSubtotal(), i.getTaxTotal(), i.getInvoiceDiscount(), i.getTotal(), i.getAmountPaid(), balance, i.getNote(), i.getPaymentInstructions(),
            i.getSourceSaleId(), i.getSourceDocumentId(), open, "DRAFT".equals(i.getStatus()), "CANCELLED".equals(i.getStatus()) ? null : shareUrl(i),
            i.getSentAt(), ls, ps);
    }

    // ---------- PDF ----------
    @Transactional(readOnly = true)
    public byte[] pdf(UUID id) { return render(find(id)); }

    /** Anyone holding the unguessable link can download the PDF. Cancelled invoices are not served. */
    @Transactional(readOnly = true)
    public byte[] publicPdf(String token) {
        Invoice i = invoices.findByShareToken(token == null ? "" : token).filter(x -> !"CANCELLED".equals(x.getStatus()))
            .orElseThrow(() -> ApiException.notFound("This invoice link is not valid"));
        return render(i);
    }

    @Transactional(readOnly = true)
    public String filenameFor(UUID id) { return InvoiceMath.number(find(id).getInvoiceNumber()) + ".pdf"; }

    private byte[] render(Invoice i) {
        Business b = businesses.findById(i.getBusinessId()).orElseThrow();
        InvoiceSettings st = settings.findById(i.getBusinessId()).orElse(null);
        List<InvoicePdf.Line> ls = items.findByInvoiceIdOrderByPosition(i.getId()).stream()
            .map(x -> new InvoicePdf.Line(x.getDescription(), x.getQuantity(), x.getUnitPrice(), x.getDiscount(), x.getTaxRate(), x.getLineTotal())).toList();
        LocalDate today = LocalDate.now(ZoneId.of(b.getTimezone() == null ? "Africa/Lagos" : b.getTimezone()));
        BigDecimal balance = i.getTotal().subtract(i.getAmountPaid());
        return InvoicePdf.render(new InvoicePdf.View(b.getName(), b.getAddress(), b.getPhone(), b.getEmail(), st == null ? null : st.getLogo(),
            InvoiceMath.number(i.getInvoiceNumber()), InvoiceMath.displayStatus(i.getStatus(), i.getTotal(), i.getAmountPaid(), i.getDueDate(), today),
            i.getIssueDate(), i.getDueDate(), i.getPaymentTerms(), i.getCustomerName(), i.getCustomerPhone(), i.getCustomerEmail(), i.getCustomerAddress(),
            ls, i.getSubtotal(), i.getTaxTotal(), i.getInvoiceDiscount(), i.getTotal(), i.getAmountPaid(), balance, i.getPaymentInstructions(), i.getNote()));
    }

    // ---------- settings ----------
    @Transactional(readOnly = true)
    public SettingsDto settings() {
        InvoiceSettings s = settings.findById(Tenant.businessId()).orElse(null);
        return s == null ? new SettingsDto(null, null, BigDecimal.ZERO, false)
            : new SettingsDto(s.getPaymentInstructions(), s.getDefaultTerms(), s.getDefaultTaxRate(), s.getLogo() != null && s.getLogo().length > 0);
    }

    @Transactional
    public SettingsDto saveSettings(SettingsRequest r) {
        InvoiceSettings s = settings.findById(Tenant.businessId()).orElseGet(() -> { InvoiceSettings n = new InvoiceSettings(); n.setBusinessId(Tenant.businessId()); return n; });
        s.setPaymentInstructions(blankToNull(r.paymentInstructions()));
        s.setDefaultTerms(blankToNull(r.defaultTerms()));
        s.setDefaultTaxRate(r.defaultTaxRate() == null ? BigDecimal.ZERO : r.defaultTaxRate());
        settings.save(s);
        return settings();
    }

    @Transactional
    public SettingsDto saveLogo(byte[] data, String type) {
        if (data == null || data.length == 0) throw ApiException.badRequest("Choose an image");
        if (data.length > MAX_LOGO_BYTES) throw ApiException.badRequest("The logo must be smaller than 300 KB");
        if (type == null || !(type.equals("image/png") || type.equals("image/jpeg"))) throw ApiException.badRequest("The logo must be a PNG or JPEG image");
        try { if (javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data)) == null) throw new IllegalArgumentException(); }
        catch (Exception e) { throw ApiException.badRequest("That file is not a readable image"); }
        InvoiceSettings s = settings.findById(Tenant.businessId()).orElseGet(() -> { InvoiceSettings n = new InvoiceSettings(); n.setBusinessId(Tenant.businessId()); return n; });
        s.setLogo(data);
        s.setLogoType(type);
        settings.save(s);
        return settings();
    }

    @Transactional
    public SettingsDto removeLogo() {
        settings.findById(Tenant.businessId()).ifPresent(s -> { s.setLogo(null); s.setLogoType(null); settings.save(s); });
        return settings();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
