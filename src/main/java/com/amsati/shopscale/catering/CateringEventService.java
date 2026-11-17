package com.amsati.shopscale.catering;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.business.Business;
import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.catalog.ProductVariant;
import com.amsati.shopscale.catalog.ProductVariantRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.common.CounterService;
import com.amsati.shopscale.common.Money;
import com.amsati.shopscale.common.PaymentMethod;
import com.amsati.shopscale.customer.Customer;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.expense.ExpenseService;
import com.amsati.shopscale.finance.FinanceService;
import com.amsati.shopscale.industry.IndustryGuard;
import com.amsati.shopscale.industry.Workflow;
import com.amsati.shopscale.inventory.MovementType;
import com.amsati.shopscale.inventory.StockService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catering events from first enquiry to final payment. One record per event holds the quote, the money received, the
 * preparation plan, the stock that was used and the costs, so the profit figure always comes from the same rows.
 * Prices, totals and balances are worked out here from the package and service prices; the client never supplies them.
 * Money is posted to the cash ledger once per payment (guarded by a request id) and stock leaves once per event (guarded by
 * catering_events.stock_issued_at).
 */
@Service
@RequiredArgsConstructor
public class CateringEventService {
    private final JdbcTemplate jdbc;
    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final ProductVariantRepository variants;
    private final StockService stock;
    private final CounterService counters;
    private final FinanceService finance;
    private final ExpenseService expenses;
    private final CateringCatalogService catalog;
    private final IndustryGuard industry;
    private final SubscriptionService subscriptions;
    private final AccessService access;
    private final AuditService audit;

    private static final List<String> LIVE = List.of("BOOKED", "IN_PREPARATION", "READY");
    private static final List<String> OWING = List.of("BOOKED", "IN_PREPARATION", "READY", "COMPLETED");

    // ---------------------------------------------------------------- requests and views
    public record ServiceLine(@NotNull UUID serviceId, @Min(1) @Max(1000) int quantity) {}
    public record EventRequest(@NotBlank @Size(max = 150) String title, UUID customerId, @Size(max = 150) String customerName, @Size(max = 40) String customerPhone,
                               @Size(max = 250) String venue, @NotNull LocalDate eventDate, LocalTime startTime, @Min(1) @Max(100000) int guests,
                               @Size(max = 2000) String requirements, UUID packageId, @Valid List<ServiceLine> services,
                               @DecimalMin("0") BigDecimal discount, @DecimalMin("0") BigDecimal depositRequired, LocalDate quoteValidUntil, UUID branchId) {}
    public record PaymentRequest(@NotNull @DecimalMin(value = "0.01", message = "Enter an amount above zero") BigDecimal amount, @NotNull PaymentMethod method,
                                 @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}
    public record CancelRequest(@NotNull @Size(min = 3, max = 300, message = "Say why this event is being cancelled") String reason,
                                @DecimalMin("0") BigDecimal refundAmount, PaymentMethod refundMethod) {}
    public record ConfirmRequest(boolean allowSameDay) {}
    public record TaskRequest(@NotBlank @Size(max = 200) String title) {}
    public record TaskDone(boolean done) {}
    public record UsageRequest(@NotNull UUID variantId, @NotNull String kind, @NotNull @DecimalMin("0.001") BigDecimal quantity, @Size(max = 300) String note, @Size(max = 64) String clientRequestId) {}
    public record CostRequest(@NotBlank @Size(max = 80) String category, @Size(max = 300) String description, @NotNull @DecimalMin("0.01") BigDecimal amount,
                              boolean estimated, PaymentMethod paymentMethod, @Size(max = 64) String clientRequestId) {}
    public record PreviewRequest(UUID packageId, @Min(1) int guests, @Valid List<ServiceLine> services, @DecimalMin("0") BigDecimal discount) {}

    public record ServiceDto(UUID id, String name, String basis, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {}
    public record PaymentDto(UUID id, String kind, String method, BigDecimal amount, String note, Instant createdAt) {}
    public record TaskDto(UUID id, String title, boolean done, Instant doneAt) {}
    public record PlanDto(UUID variantId, String name, String unit, BigDecimal planned, BigDecimal issued, BigDecimal extra, BigDecimal returned, BigDecimal wasted, BigDecimal used, BigDecimal onHand, BigDecimal unitCost) {}
    public record CostDto(UUID id, String category, String description, BigDecimal amount, boolean estimated, Instant createdAt) {}
    public record Summary(BigDecimal revenue, BigDecimal received, BigDecimal balance, BigDecimal estimatedIngredients, BigDecimal estimatedOther, BigDecimal estimatedCost,
                          BigDecimal actualIngredients, BigDecimal actualOther, BigDecimal actualCost, BigDecimal estimatedProfit, BigDecimal grossProfit, BigDecimal marginPercent) {}
    public record Quote(BigDecimal basePrice, BigDecimal pricePerGuest, int billedGuests, BigDecimal packageTotal, List<ServiceDto> services, BigDecimal servicesTotal,
                        BigDecimal discount, BigDecimal total) {}
    public record EventDto(UUID id, long number, String title, UUID customerId, String customerName, String customerPhone, String venue, LocalDate eventDate, LocalTime startTime,
                           int guests, String requirements, String status, UUID packageId, String packageName, Quote quote, BigDecimal depositRequired, LocalDate quoteValidUntil,
                           BigDecimal received, BigDecimal balance, boolean stockIssued, Instant createdAt, Instant confirmedAt, Instant completedAt, String cancelReason,
                           List<PaymentDto> payments, List<TaskDto> tasks, List<PlanDto> plan, List<CostDto> costs, Summary summary, boolean showMoney) {}
    public record Row(UUID id, long number, String title, String customerName, String venue, LocalDate eventDate, int guests, String status, BigDecimal total, BigDecimal received, BigDecimal balance) {}
    public record Dashboard(List<Row> upcoming, List<Row> pending, long pendingCount, long bookedCount, long inPreparationCount, long readyCount, long completedThisMonth,
                            BigDecimal outstanding, BigDecimal receivedThisMonth, BigDecimal profitThisMonth, BigDecimal revenueThisMonth, boolean showMoney) {}

    /** Raw event row. */
    private record Ev(UUID id, UUID branchId, long number, String title, UUID customerId, String customerName, String customerPhone, String venue, LocalDate eventDate,
                      LocalTime startTime, int guests, String requirements, String status, UUID packageId, String packageName, BigDecimal basePrice, BigDecimal perGuest,
                      BigDecimal servicesTotal, BigDecimal discount, BigDecimal total, BigDecimal depositRequired, LocalDate validUntil, Instant createdAt, Instant confirmedAt,
                      Instant completedAt, String cancelReason, Instant stockIssuedAt) {}

    private static final String EV_COLS = "id, branch_id, number, title, customer_id, customer_name, customer_phone, venue, event_date, start_time, guests, requirements, status, package_id, package_name, "
        + "base_price, price_per_guest, services_total, discount, total, deposit_required, quote_valid_until, created_at, confirmed_at, completed_at, cancel_reason, stock_issued_at";

    private static Instant ts(java.sql.ResultSet rs, String c) throws java.sql.SQLException { Timestamp t = rs.getTimestamp(c); return t == null ? null : t.toInstant(); }

    private static final org.springframework.jdbc.core.RowMapper<Ev> EV = (rs, i) -> new Ev(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class), rs.getLong("number"),
        rs.getString("title"), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getString("customer_phone"), rs.getString("venue"),
        rs.getObject("event_date", LocalDate.class), rs.getObject("start_time", LocalTime.class), rs.getInt("guests"), rs.getString("requirements"), rs.getString("status"),
        rs.getObject("package_id", UUID.class), rs.getString("package_name"), rs.getBigDecimal("base_price"), rs.getBigDecimal("price_per_guest"), rs.getBigDecimal("services_total"),
        rs.getBigDecimal("discount"), rs.getBigDecimal("total"), rs.getBigDecimal("deposit_required"), rs.getObject("quote_valid_until", LocalDate.class),
        ts(rs, "created_at"), ts(rs, "confirmed_at"), ts(rs, "completed_at"), rs.getString("cancel_reason"), ts(rs, "stock_issued_at"));

    private UUID gate() {
        UUID bid = Tenant.businessId();
        industry.require(bid, Workflow.CATERING);
        subscriptions.requireFeature(bid, "CATERING", "Catering");
        return bid;
    }

    private boolean money() { return access.current().contains(Permission.CATERING_MANAGE); }

    private Ev load(UUID bid, UUID id, boolean lock) {
        List<Ev> l = jdbc.query("select " + EV_COLS + " from catering_events where id = ? and business_id = ?" + (lock ? " for update" : ""), EV, id, bid);
        if (l.isEmpty()) throw ApiException.notFound("Event not found");
        return l.get(0);
    }

    private LocalDate today(UUID bid) { return LocalDate.now(businesses.findById(bid).orElseThrow(() -> ApiException.notFound("Business not found")).zone()); }

    // ---------------------------------------------------------------- pricing (server-side)
    private record Priced(BigDecimal base, BigDecimal perGuest, String packageName, Quote quote) {}

    /** Works out the quote. {@code keep} holds prices already agreed for this event so a booked price does not move when the price list changes. */
    private Priced price(UUID bid, UUID packageId, int guests, List<ServiceLine> lines, BigDecimal discount, Ev keep) {
        BigDecimal base = BigDecimal.ZERO, per = BigDecimal.ZERO;
        String pname = null;
        int min = 1;
        if (packageId != null) {
            List<Object[]> p = jdbc.query("select name, base_price, price_per_guest, min_guests, active from catering_packages where id = ? and business_id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getInt(4), rs.getBoolean(5)}, packageId, bid);
            if (p.isEmpty()) throw ApiException.badRequest("Package not found");
            Object[] r = p.get(0);
            pname = (String) r[0];
            boolean same = keep != null && packageId.equals(keep.packageId());
            if (!same && !(Boolean) r[4]) throw ApiException.badRequest(pname + " is no longer offered");
            base = same ? keep.basePrice() : (BigDecimal) r[1];
            per = same ? keep.perGuest() : (BigDecimal) r[2];
            min = (Integer) r[3];
        }
        int billed = packageId == null ? guests : Math.max(guests, min);
        BigDecimal packageTotal = Money.scale(base.add(per.multiply(BigDecimal.valueOf(billed))));
        Map<UUID, ServiceDto> kept = new HashMap<>();
        if (keep != null) for (ServiceDto s : services(keep.id())) if (s.id() != null) kept.put(s.id(), s);
        List<ServiceDto> svc = new ArrayList<>();
        BigDecimal svcTotal = BigDecimal.ZERO;
        Set<UUID> seen = new HashSet<>();
        for (ServiceLine l : lines == null ? List.<ServiceLine>of() : lines) {
            if (!seen.add(l.serviceId())) throw ApiException.badRequest("Each additional service can only be added once. Change its quantity instead.");
            List<Object[]> s = jdbc.query("select name, price, basis, active from catering_services where id = ? and business_id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getBoolean(4)}, l.serviceId(), bid);
            if (s.isEmpty()) throw ApiException.badRequest("A selected service was not found");
            Object[] r = s.get(0);
            ServiceDto prior = kept.get(l.serviceId());
            if (prior == null && !(Boolean) r[3]) throw ApiException.badRequest((String) r[0] + " is no longer offered");
            BigDecimal unit = prior != null ? prior.unitPrice() : (BigDecimal) r[1];
            BigDecimal line = Money.scale("PER_GUEST".equals(r[2]) ? unit.multiply(BigDecimal.valueOf(guests)).multiply(BigDecimal.valueOf(l.quantity())) : unit.multiply(BigDecimal.valueOf(l.quantity())));
            svc.add(new ServiceDto(l.serviceId(), (String) r[0], (String) r[2], unit, l.quantity(), line));
            svcTotal = svcTotal.add(line);
        }
        BigDecimal disc = Money.scale(discount);
        BigDecimal subtotal = packageTotal.add(svcTotal);
        if (disc.compareTo(subtotal) > 0) throw ApiException.badRequest("The discount is more than the quote total");
        return new Priced(base, per, pname, new Quote(base, per, billed, packageTotal, svc, Money.scale(svcTotal), disc, subtotal.subtract(disc)));
    }

    @Transactional(readOnly = true)
    public Quote preview(PreviewRequest r) {
        UUID bid = gate();
        return price(bid, r.packageId(), r.guests(), r.services(), r.discount(), null).quote();
    }

    private List<ServiceDto> services(UUID eventId) {
        return jdbc.query("select service_id, name, basis, unit_price, quantity, line_total from catering_event_services where event_id = ? order by name",
            (rs, i) -> new ServiceDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getInt(5), rs.getBigDecimal(6)), eventId);
    }

    private void storeQuote(Ev e, Priced p) {
        jdbc.update("delete from catering_event_services where event_id = ?", e.id());
        for (ServiceDto s : p.quote().services()) jdbc.update("insert into catering_event_services (id, event_id, service_id, name, basis, unit_price, quantity, line_total) values (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), e.id(), s.id(), s.name(), s.basis(), s.unitPrice(), s.quantity(), s.lineTotal());
        jdbc.update("update catering_events set package_name = ?, base_price = ?, price_per_guest = ?, services_total = ?, discount = ?, total = ?, updated_at = now() where id = ?",
            p.packageName(), p.base(), p.perGuest(), p.quote().servicesTotal(), p.quote().discount(), p.quote().total(), e.id());
    }

    // ---------------------------------------------------------------- create / edit
    @Transactional
    public EventDto create(EventRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        String[] who = who(bid, r);
        if (r.eventDate().isBefore(today(bid))) throw ApiException.badRequest("The event date is in the past");
        UUID branch = stock.resolveBranch(bid, r.branchId());
        UUID id = UUID.randomUUID();
        Priced p = price(bid, r.packageId(), r.guests(), r.services(), r.discount(), null);
        BigDecimal deposit = deposit(r.depositRequired(), p.quote().total());
        jdbc.update("insert into catering_events (id, business_id, branch_id, number, title, customer_id, customer_name, customer_phone, venue, event_date, start_time, guests, requirements, status, package_id, deposit_required, quote_valid_until, created_by) "
            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?, 'ENQUIRY', ?,?,?,?)", id, bid, branch, counters.next(bid, "CATERING_EVENT"), r.title().trim(), r.customerId(), who[0], who[1], blank(r.venue()),
            r.eventDate(), r.startTime(), r.guests(), blank(r.requirements()), r.packageId(), deposit, r.quoteValidUntil(), Tenant.userId());
        storeQuote(load(bid, id, false), p);
        Ev e = load(bid, id, false);
        audit.record(bid, Tenant.userId(), "CATERING_EVENT_CREATED", "catering_event", id, "#" + e.number() + " " + e.title());
        return view(bid, e);
    }

    private String[] who(UUID bid, EventRequest r) {
        String name = blank(r.customerName()), phone = blank(r.customerPhone());
        if (r.customerId() != null) {
            Customer c = customers.findById(r.customerId()).filter(x -> x.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.badRequest("Customer not found"));
            if (name == null) name = c.getName();
            if (phone == null) phone = c.getPhone();
        }
        if (name == null) throw ApiException.badRequest("Enter the customer's name");
        return new String[]{name, phone};
    }

    private static BigDecimal deposit(BigDecimal d, BigDecimal total) {
        BigDecimal v = d == null ? BigDecimal.ZERO : Money.scale(d);
        if (v.compareTo(total) > 0) throw ApiException.badRequest("The deposit is more than the quote total");
        return v;
    }

    @Transactional
    public EventDto update(UUID id, EventRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        Ev e = load(bid, id, true);
        if (!List.of("ENQUIRY", "QUOTED", "BOOKED").contains(e.status())) throw ApiException.conflict("This event can no longer be edited (" + e.status().toLowerCase().replace('_', ' ') + ")");
        boolean booked = "BOOKED".equals(e.status());
        if (booked && !Objects.equals(r.packageId(), e.packageId())) throw ApiException.conflict("A booked event keeps its package. Cancel the booking to change it.");
        if (r.eventDate().isBefore(today(bid)) && !r.eventDate().equals(e.eventDate())) throw ApiException.badRequest("The event date is in the past");
        String[] who = who(bid, r);
        Priced p = price(bid, r.packageId(), r.guests(), r.services(), r.discount(), booked || "QUOTED".equals(e.status()) ? e : null);
        BigDecimal deposit = deposit(r.depositRequired(), p.quote().total());
        if (booked && p.quote().total().compareTo(received(e.id())) < 0) throw ApiException.conflict("The new total is less than the money already received. Refund the difference first.");
        jdbc.update("update catering_events set title=?, customer_id=?, customer_name=?, customer_phone=?, venue=?, event_date=?, start_time=?, guests=?, requirements=?, package_id=?, deposit_required=?, quote_valid_until=?, updated_at=now() where id=?",
            r.title().trim(), r.customerId(), who[0], who[1], blank(r.venue()), r.eventDate(), r.startTime(), r.guests(), blank(r.requirements()), r.packageId(), deposit, r.quoteValidUntil(), id);
        storeQuote(e, p);
        // A changed quote must be sent again before it can be confirmed.
        if ("QUOTED".equals(e.status()) && p.quote().total().compareTo(e.total()) != 0) jdbc.update("update catering_events set status = 'ENQUIRY' where id = ?", id);
        audit.record(bid, Tenant.userId(), "CATERING_EVENT_UPDATED", "catering_event", id, "#" + e.number() + " total " + p.quote().total().toPlainString());
        return view(bid, load(bid, id, false));
    }

    // ---------------------------------------------------------------- lifecycle
    @Transactional
    public EventDto quote(UUID id) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if ("QUOTED".equals(e.status())) return view(bid, e);
        if (!"ENQUIRY".equals(e.status())) throw ApiException.conflict("Only an enquiry can be quoted");
        if (e.total().signum() <= 0) throw ApiException.badRequest("Choose a package or add services so the quotation has a price");
        LocalDate until = e.validUntil() != null && !e.validUntil().isBefore(today(bid)) ? e.validUntil() : today(bid).plusDays(14);
        jdbc.update("update catering_events set status = 'QUOTED', quoted_at = now(), quote_valid_until = ?, updated_at = now() where id = ?", until, id);
        audit.record(bid, Tenant.userId(), "CATERING_QUOTE_ISSUED", "catering_event", id, "#" + e.number() + " " + e.total().toPlainString());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto confirm(UUID id, ConfirmRequest c) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        Ev e = load(bid, id, true);
        if ("BOOKED".equals(e.status())) return view(bid, e);
        if (!"QUOTED".equals(e.status())) throw ApiException.conflict("Send a quotation before confirming the booking");
        if (e.eventDate().isBefore(today(bid))) throw ApiException.conflict("The event date has passed");
        if (e.validUntil() != null && e.validUntil().isBefore(today(bid))) throw ApiException.conflict("The quotation has expired. Update the validity date first.");
        BigDecimal got = received(id);
        if (got.compareTo(e.depositRequired()) < 0) throw ApiException.conflict("Receive the deposit of " + e.depositRequired().toPlainString() + " before confirming. Received so far: " + got.toPlainString() + ".");
        if (!(c != null && c.allowSameDay()) && e.venue() != null) {
            List<Long> clash = jdbc.queryForList("select number from catering_events where business_id = ? and id <> ? and event_date = ? and lower(venue) = lower(?) and status in ('BOOKED','IN_PREPARATION','READY')", Long.class, bid, id, e.eventDate(), e.venue());
            if (!clash.isEmpty()) throw ApiException.conflict("Event #" + clash.get(0) + " is already booked at this venue on that day. Confirm again to book both.");
        }
        jdbc.update("update catering_events set status = 'BOOKED', confirmed_at = now(), updated_at = now() where id = ?", id);
        audit.record(bid, Tenant.userId(), "CATERING_BOOKING_CONFIRMED", "catering_event", id, "#" + e.number());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto startPreparation(UUID id) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        Ev e = load(bid, id, true);
        if ("IN_PREPARATION".equals(e.status())) return view(bid, e);
        if (!"BOOKED".equals(e.status())) throw ApiException.conflict("Only a confirmed booking can go into preparation");
        // The plan is made once, from the confirmed package and guest count.
        Map<UUID, BigDecimal> need = catalog.requirements(bid, e.packageId(), e.guests());
        if (!need.isEmpty()) {
            Map<UUID, ProductVariant> vs = variants.findAllById(need.keySet()).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
            for (Map.Entry<UUID, BigDecimal> n : need.entrySet()) {
                ProductVariant v = vs.get(n.getKey());
                jdbc.update("insert into catering_plan_lines (id, event_id, variant_id, item_name, unit, planned_qty, unit_cost) values (?,?,?,?,?,?,?) on conflict (event_id, variant_id) do nothing",
                    UUID.randomUUID(), id, v.getId(), name(v), v.getUnit(), n.getValue(), v.getCost());
            }
        }
        Integer tasks = jdbc.queryForObject("select count(*) from catering_tasks where event_id = ?", Integer.class, id);
        if (tasks == null || tasks == 0) {
            String[] defaults = {"Confirm the final guest count with the customer", "Buy or check all ingredients", "Prepare the food", "Pack, label and load", "Confirm delivery time and venue access"};
            for (int i = 0; i < defaults.length; i++) jdbc.update("insert into catering_tasks (id, event_id, title, sort_order) values (?,?,?,?)", UUID.randomUUID(), id, defaults[i], i);
        }
        jdbc.update("update catering_events set status = 'IN_PREPARATION', prep_started_at = now(), updated_at = now() where id = ?", id);
        audit.record(bid, Tenant.userId(), "CATERING_PREPARATION_STARTED", "catering_event", id, "#" + e.number());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto ready(UUID id) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if ("READY".equals(e.status())) return view(bid, e);
        if (!"IN_PREPARATION".equals(e.status())) throw ApiException.conflict("The event is not in preparation");
        Integer open = jdbc.queryForObject("select count(*) from catering_tasks where event_id = ? and not done", Integer.class, id);
        if (open != null && open > 0) throw ApiException.conflict(open + " checklist item(s) are not done yet");
        Integer lines = jdbc.queryForObject("select count(*) from catering_plan_lines where event_id = ?", Integer.class, id);
        if (lines != null && lines > 0 && e.stockIssuedAt() == null) throw ApiException.conflict("Take the ingredients out of stock before marking the event ready");
        jdbc.update("update catering_events set status = 'READY', ready_at = now(), updated_at = now() where id = ?", id);
        audit.record(bid, Tenant.userId(), "CATERING_EVENT_READY", "catering_event", id, "#" + e.number());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto complete(UUID id) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if ("COMPLETED".equals(e.status())) return view(bid, e);
        if (!"READY".equals(e.status())) throw ApiException.conflict("Mark the event ready before completing it");
        jdbc.update("update catering_events set status = 'COMPLETED', completed_at = now(), updated_at = now() where id = ?", id);
        audit.record(bid, Tenant.userId(), "CATERING_EVENT_COMPLETED", "catering_event", id, "#" + e.number());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto cancel(UUID id, CancelRequest r) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if ("CANCELLED".equals(e.status())) return view(bid, e);
        if ("COMPLETED".equals(e.status())) throw ApiException.conflict("A completed event cannot be cancelled");
        BigDecimal got = received(id);
        BigDecimal refund = r.refundAmount() == null ? BigDecimal.ZERO : Money.scale(r.refundAmount());
        if (refund.compareTo(got) > 0) throw ApiException.badRequest("You can refund at most " + got.toPlainString() + ", the amount received");
        if (refund.signum() > 0) {
            PaymentMethod m = r.refundMethod() == null ? PaymentMethod.CASH : r.refundMethod();
            jdbc.update("insert into catering_payments (id, business_id, event_id, kind, method, amount, note, created_by) values (?,?,?, 'REFUND', ?,?,?,?)",
                UUID.randomUUID(), bid, id, m.name(), refund, "Refund on cancellation", Tenant.userId());
            finance.record(bid, m, false, refund, "REFUND", "CATERING_EVENT", id, "Refund for event #" + e.number(), Tenant.userId(), e.branchId());
        }
        jdbc.update("update catering_events set status = 'CANCELLED', cancelled_at = now(), cancel_reason = ?, updated_at = now() where id = ?", r.reason().trim(), id);
        audit.record(bid, Tenant.userId(), "CATERING_EVENT_CANCELLED", "catering_event", id, "#" + e.number() + ": " + r.reason().trim() + (refund.signum() > 0 ? " refund " + refund.toPlainString() : ""));
        return view(bid, load(bid, id, false));
    }

    // ---------------------------------------------------------------- payments
    private BigDecimal received(UUID eventId) {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(case when kind = 'REFUND' then -amount else amount end), 0) from catering_payments where event_id = ?", BigDecimal.class, eventId);
        return v == null ? BigDecimal.ZERO : Money.scale(v);
    }

    /** Records money from the customer. The same request id never records twice; a payment can never exceed the balance. */
    @Transactional
    public EventDto pay(UUID id, PaymentRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        String rid = blank(r.clientRequestId());
        if (rid != null && !jdbc.queryForList("select 1 from catering_payments where business_id = ? and client_request_id = ?", bid, rid).isEmpty()) return view(bid, load(bid, id, false));
        Ev e = load(bid, id, true);
        if (!List.of("QUOTED", "BOOKED", "IN_PREPARATION", "READY", "COMPLETED").contains(e.status())) throw ApiException.conflict("Payments can be taken once a quotation has been sent");
        BigDecimal amount = Money.scale(r.amount());
        BigDecimal got = received(id);
        BigDecimal balance = e.total().subtract(got);
        if (amount.compareTo(balance) > 0) throw ApiException.badRequest("That is more than the balance of " + balance.toPlainString());
        String kind = got.signum() == 0 ? "DEPOSIT" : "PAYMENT";
        jdbc.update("insert into catering_payments (id, business_id, event_id, kind, method, amount, note, client_request_id, created_by) values (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), bid, id, kind, r.method().name(), amount, blank(r.note()), rid, Tenant.userId());
        finance.record(bid, r.method(), true, amount, "CATERING", "CATERING_EVENT", id, ("DEPOSIT".equals(kind) ? "Deposit" : "Payment") + " for event #" + e.number(), Tenant.userId(), e.branchId());
        audit.record(bid, Tenant.userId(), "CATERING_PAYMENT", "catering_event", id, "#" + e.number() + " " + kind.toLowerCase() + " " + amount.toPlainString());
        return view(bid, load(bid, id, false));
    }

    // ---------------------------------------------------------------- preparation checklist
    @Transactional
    public EventDto addTask(UUID id, TaskRequest r) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if (!LIVE.contains(e.status())) throw ApiException.conflict("The checklist can be changed while the event is booked or in preparation");
        jdbc.update("insert into catering_tasks (id, event_id, title, sort_order) values (?,?,?, coalesce((select max(sort_order) + 1 from catering_tasks where event_id = ?), 0))", UUID.randomUUID(), id, r.title().trim(), id);
        return view(bid, e);
    }

    @Transactional
    public EventDto setTask(UUID id, UUID taskId, TaskDone d) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        if (!LIVE.contains(e.status())) throw ApiException.conflict("The checklist can be changed while the event is booked or in preparation");
        int n = d.done()
            ? jdbc.update("update catering_tasks set done = true, done_at = coalesce(done_at, now()), done_by = ? where id = ? and event_id = ?", Tenant.userId(), taskId, id)
            : jdbc.update("update catering_tasks set done = false, done_at = null, done_by = null where id = ? and event_id = ?", taskId, id);
        if (n == 0) throw ApiException.notFound("Checklist item not found");
        return view(bid, e);
    }

    // ---------------------------------------------------------------- stock
    /** Takes the planned ingredients out of stock. Happens once per event: a second call (or a double tap) changes nothing. */
    @Transactional
    public EventDto issueStock(UUID id) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        Ev e = load(bid, id, true);
        if (e.stockIssuedAt() != null) return view(bid, e);
        if (!List.of("IN_PREPARATION", "READY").contains(e.status())) throw ApiException.conflict("Start preparation first");
        List<Object[]> lines = jdbc.query("select variant_id, planned_qty, unit_cost from catering_plan_lines where event_id = ?", (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3)}, id);
        if (lines.isEmpty()) throw ApiException.badRequest("This event has no ingredient plan. Use a package with ingredients, or just mark the event ready.");
        boolean allowNegative = businesses.findById(bid).map(Business::isAllowNegativeStock).orElse(false);
        List<UUID> ids = lines.stream().map(l -> (UUID) l[0]).sorted().toList();
        Map<UUID, ProductVariant> vs = variants.lockAll(bid, new LinkedHashSet<>(ids)).stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        // The guard row: only one request can flip this from null, and a failure below rolls it back with everything else.
        int flipped = jdbc.update("update catering_events set stock_issued_at = now() where id = ? and stock_issued_at is null", id);
        if (flipped == 0) return view(bid, load(bid, id, false));
        String ref = "EVENT-" + e.number();
        for (Object[] l : lines) {
            ProductVariant v = vs.get((UUID) l[0]);
            BigDecimal qty = (BigDecimal) l[1];
            if (!allowNegative && stock.onHand(v, e.branchId()).compareTo(qty) < 0) throw ApiException.conflict("Not enough " + name(v) + ". Need " + qty.stripTrailingZeros().toPlainString() + ", have " + stock.onHand(v, e.branchId()).stripTrailingZeros().toPlainString() + ".");
            stock.apply(v, e.branchId(), MovementType.PRODUCTION_OUT, qty.negate(), "Catering event", ref, Tenant.userId(), allowNegative);
            jdbc.update("insert into catering_usage (id, business_id, event_id, variant_id, kind, quantity, unit_cost, note, created_by) values (?,?,?,?, 'ISSUE', ?,?,?,?)",
                UUID.randomUUID(), bid, id, v.getId(), qty, l[2], "Planned ingredients", Tenant.userId());
        }
        audit.record(bid, Tenant.userId(), "CATERING_STOCK_ISSUED", "catering_event", id, ref + " " + lines.size() + " ingredients");
        return view(bid, load(bid, id, false));
    }

    /** Extra use, unused ingredients coming back, or waste, after the plan was issued. */
    @Transactional
    public EventDto usage(UUID id, UsageRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        if (!Set.of("EXTRA", "RETURN", "WASTE").contains(r.kind())) throw ApiException.badRequest("Choose extra use, returned or wasted");
        String rid = blank(r.clientRequestId());
        if (rid != null && !jdbc.queryForList("select 1 from catering_usage where business_id = ? and client_request_id = ?", bid, rid).isEmpty()) return view(bid, load(bid, id, false));
        Ev e = load(bid, id, true);
        if (e.stockIssuedAt() == null) throw ApiException.conflict("Take the ingredients out of stock first");
        List<ProductVariant> locked = variants.lockAll(bid, Set.of(r.variantId()));
        if (locked.isEmpty()) throw ApiException.badRequest("That item was not found");
        ProductVariant v = locked.get(0);
        boolean allowNegative = businesses.findById(bid).map(Business::isAllowNegativeStock).orElse(false);
        String ref = "EVENT-" + e.number();
        BigDecimal unitCost = jdbc.query("select unit_cost from catering_plan_lines where event_id = ? and variant_id = ?", (rs, i) -> rs.getBigDecimal(1), id, v.getId()).stream().findFirst().orElse(v.getCost());
        if ("RETURN".equals(r.kind())) {
            BigDecimal net = netUsed(id, v.getId());
            if (r.quantity().compareTo(net) > 0) throw ApiException.badRequest("Only " + net.stripTrailingZeros().toPlainString() + " of " + name(v) + " is out for this event");
            stock.apply(v, e.branchId(), MovementType.PRODUCTION_IN, r.quantity(), "Returned from event", ref, Tenant.userId(), true);
        } else {
            stock.apply(v, e.branchId(), "WASTE".equals(r.kind()) ? MovementType.WASTAGE : MovementType.PRODUCTION_OUT, r.quantity().negate(), ("WASTE".equals(r.kind()) ? "Wasted at event" : "Extra used at event"), ref, Tenant.userId(), allowNegative);
        }
        jdbc.update("insert into catering_usage (id, business_id, event_id, variant_id, kind, quantity, unit_cost, note, client_request_id, created_by) values (?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), bid, id, v.getId(), r.kind(), r.quantity(), unitCost, blank(r.note()), rid, Tenant.userId());
        audit.record(bid, Tenant.userId(), "CATERING_STOCK_" + r.kind(), "catering_event", id, ref + " " + name(v) + " " + r.quantity().stripTrailingZeros().toPlainString());
        return view(bid, load(bid, id, false));
    }

    private BigDecimal netUsed(UUID eventId, UUID variantId) {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(case when kind = 'RETURN' then -quantity else quantity end), 0) from catering_usage where event_id = ? and variant_id = ?", BigDecimal.class, eventId, variantId);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------------------------------------------------------------- costs
    @Transactional
    public EventDto addCost(UUID id, CostRequest r) {
        UUID bid = gate();
        subscriptions.requireWritable(bid);
        String rid = blank(r.clientRequestId());
        if (rid != null && !jdbc.queryForList("select 1 from catering_costs where business_id = ? and client_request_id = ?", bid, rid).isEmpty()) return view(bid, load(bid, id, false));
        Ev e = load(bid, id, true);
        if ("CANCELLED".equals(e.status())) throw ApiException.conflict("This event was cancelled");
        UUID expenseId = null;
        if (!r.estimated()) {
            if (r.paymentMethod() == null) throw ApiException.badRequest("Choose how this was paid");
            // Real spending goes through Expenses so it also shows in the business's own expense and cash reports.
            var ex = expenses.create(new ExpenseService.ExpenseRequest("Catering: " + r.category().trim(), r.amount(), null,
                "Event #" + e.number() + (blank(r.description()) == null ? "" : " - " + r.description().trim()), r.paymentMethod(), rid == null ? null : "ce-" + rid));
            expenseId = ex.id();
        }
        jdbc.update("insert into catering_costs (id, business_id, event_id, category, description, amount, estimated, expense_id, client_request_id, created_by) values (?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), bid, id, r.category().trim(), blank(r.description()), Money.scale(r.amount()), r.estimated(), expenseId, rid, Tenant.userId());
        audit.record(bid, Tenant.userId(), r.estimated() ? "CATERING_COST_ESTIMATED" : "CATERING_COST_RECORDED", "catering_event", id, "#" + e.number() + " " + r.category().trim() + " " + r.amount().toPlainString());
        return view(bid, load(bid, id, false));
    }

    @Transactional
    public EventDto removeEstimate(UUID id, UUID costId) {
        UUID bid = gate();
        Ev e = load(bid, id, true);
        int n = jdbc.update("delete from catering_costs where id = ? and event_id = ? and estimated = true", costId, id);
        if (n == 0) throw ApiException.conflict("Only an estimated cost can be removed. Recorded costs are part of your expenses.");
        return view(bid, e);
    }

    // ---------------------------------------------------------------- reads
    @Transactional(readOnly = true)
    public EventDto get(UUID id) {
        UUID bid = gate();
        return view(bid, load(bid, id, false));
    }

    @Transactional(readOnly = true)
    public List<Row> list(String status, String q) {
        UUID bid = gate();
        boolean money = money();
        StringBuilder sql = new StringBuilder("select e.id, e.number, e.title, e.customer_name, e.venue, e.event_date, e.guests, e.status, e.total, "
            + "coalesce((select sum(case when p.kind = 'REFUND' then -p.amount else p.amount end) from catering_payments p where p.event_id = e.id), 0) as received from catering_events e where e.business_id = ?");
        List<Object> args = new ArrayList<>(List.of(bid));
        if (status != null && !status.isBlank()) { sql.append(" and e.status = ?"); args.add(status); }
        if (q != null && !q.isBlank()) { sql.append(" and (lower(e.title) like ? or lower(e.customer_name) like ? or cast(e.number as text) = ?)"); String like = "%" + q.trim().toLowerCase() + "%"; args.add(like); args.add(like); args.add(q.trim().replace("#", "")); }
        sql.append(" order by e.event_date desc, e.number desc limit 300");
        return jdbc.query(sql.toString(), (rs, i) -> row(rs, money), args.toArray());
    }

    private Row row(java.sql.ResultSet rs, boolean money) throws java.sql.SQLException {
        BigDecimal total = rs.getBigDecimal("total"), got = Money.scale(rs.getBigDecimal("received"));
        String st = rs.getString("status");
        BigDecimal bal = "CANCELLED".equals(st) ? BigDecimal.ZERO : total.subtract(got).max(BigDecimal.ZERO);
        return new Row(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getString("title"), rs.getString("customer_name"), rs.getString("venue"), rs.getObject("event_date", LocalDate.class),
            rs.getInt("guests"), st, money ? total : null, money ? got : null, money ? bal : null);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        UUID bid = gate();
        boolean money = money();
        LocalDate today = today(bid);
        String base = "select e.id, e.number, e.title, e.customer_name, e.venue, e.event_date, e.guests, e.status, e.total, "
            + "coalesce((select sum(case when p.kind = 'REFUND' then -p.amount else p.amount end) from catering_payments p where p.event_id = e.id), 0) as received from catering_events e where e.business_id = ? ";
        List<Row> upcoming = jdbc.query(base + "and e.status in ('BOOKED','IN_PREPARATION','READY') and e.event_date >= ? order by e.event_date, e.start_time limit 10", (rs, i) -> row(rs, money), bid, today);
        List<Row> pending = jdbc.query(base + "and e.status in ('ENQUIRY','QUOTED') order by e.event_date limit 10", (rs, i) -> row(rs, money), bid);
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select status, count(*) from catering_events where business_id = ? group by status", (rs) -> { counts.put(rs.getString(1), rs.getLong(2)); }, bid);
        LocalDate first = today.withDayOfMonth(1);
        Long doneMonth = jdbc.queryForObject("select count(*) from catering_events where business_id = ? and status = 'COMPLETED' and event_date >= ? and event_date <= ?", Long.class, bid, first, today);
        BigDecimal outstanding = BigDecimal.ZERO, profit = BigDecimal.ZERO, revenue = BigDecimal.ZERO;
        BigDecimal receivedMonth = BigDecimal.ZERO;
        if (money) {
            BigDecimal o = jdbc.queryForObject("select coalesce(sum(greatest(e.total - coalesce((select sum(case when p.kind = 'REFUND' then -p.amount else p.amount end) from catering_payments p where p.event_id = e.id), 0), 0)), 0) "
                + "from catering_events e where e.business_id = ? and e.status in ('BOOKED','IN_PREPARATION','READY','COMPLETED')", BigDecimal.class, bid);
            outstanding = o == null ? BigDecimal.ZERO : Money.scale(o);
            Business b = businesses.findById(bid).orElseThrow();
            BigDecimal rm = jdbc.queryForObject("select coalesce(sum(case when kind = 'REFUND' then -amount else amount end), 0) from catering_payments where business_id = ? and created_at >= ?", BigDecimal.class, bid, Timestamp.from(first.atStartOfDay(b.zone()).toInstant()));
            receivedMonth = rm == null ? BigDecimal.ZERO : Money.scale(rm);
            for (UUID eid : jdbc.queryForList("select id from catering_events where business_id = ? and status = 'COMPLETED' and event_date >= ? and event_date <= ? limit 300", UUID.class, bid, first, today)) {
                Summary s = summary(bid, load(bid, eid, false));
                profit = profit.add(s.grossProfit());
                revenue = revenue.add(s.revenue());
            }
        }
        return new Dashboard(upcoming, pending, counts.getOrDefault("ENQUIRY", 0L) + counts.getOrDefault("QUOTED", 0L), counts.getOrDefault("BOOKED", 0L), counts.getOrDefault("IN_PREPARATION", 0L),
            counts.getOrDefault("READY", 0L), doneMonth == null ? 0 : doneMonth, money ? outstanding : null, money ? receivedMonth : null, money ? Money.scale(profit) : null, money ? Money.scale(revenue) : null, money);
    }

    // ---------------------------------------------------------------- assembling one event
    private EventDto view(UUID bid, Ev e) {
        boolean money = money();
        BigDecimal got = received(e.id());
        List<ServiceDto> svc = services(e.id());
        int min = 1;
        if (e.packageId() != null) { Integer m = jdbc.queryForObject("select min_guests from catering_packages where id = ?", Integer.class, e.packageId()); if (m != null) min = m; }
        int billed = e.packageId() == null ? e.guests() : Math.max(e.guests(), min);
        BigDecimal packageTotal = Money.scale(e.basePrice().add(e.perGuest().multiply(BigDecimal.valueOf(billed))));
        Quote quote = money ? new Quote(e.basePrice(), e.perGuest(), billed, packageTotal, svc, e.servicesTotal(), e.discount(), e.total()) : null;
        List<TaskDto> tasks = jdbc.query("select id, title, done, done_at from catering_tasks where event_id = ? order by sort_order, title", (rs, i) -> new TaskDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), ts(rs, "done_at")), e.id());
        List<PlanDto> plan = plan(e, money);
        List<PaymentDto> pays = !money ? List.of() : jdbc.query("select id, kind, method, amount, note, created_at from catering_payments where event_id = ? order by created_at",
            (rs, i) -> new PaymentDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), ts(rs, "created_at")), e.id());
        List<CostDto> costs = !money ? List.of() : jdbc.query("select id, category, description, amount, estimated, created_at from catering_costs where event_id = ? order by created_at",
            (rs, i) -> new CostDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBoolean(5), ts(rs, "created_at")), e.id());
        BigDecimal balance = "CANCELLED".equals(e.status()) ? BigDecimal.ZERO : e.total().subtract(got).max(BigDecimal.ZERO);
        return new EventDto(e.id(), e.number(), e.title(), e.customerId(), e.customerName(), e.customerPhone(), e.venue(), e.eventDate(), e.startTime(), e.guests(), e.requirements(), e.status(),
            e.packageId(), e.packageName(), quote, money ? e.depositRequired() : null, e.validUntil(), money ? got : null, money ? balance : null, e.stockIssuedAt() != null, e.createdAt(), e.confirmedAt(),
            e.completedAt(), e.cancelReason(), pays, tasks, plan, costs, money ? summary(bid, e) : null, money);
    }

    private List<PlanDto> plan(Ev e, boolean money) {
        return jdbc.query("select l.variant_id, l.item_name, l.unit, l.planned_qty, l.unit_cost, "
            + "coalesce(sum(u.quantity) filter (where u.kind = 'ISSUE'), 0) as issued, coalesce(sum(u.quantity) filter (where u.kind = 'EXTRA'), 0) as extra, "
            + "coalesce(sum(u.quantity) filter (where u.kind = 'RETURN'), 0) as returned, coalesce(sum(u.quantity) filter (where u.kind = 'WASTE'), 0) as wasted, "
            + "(select coalesce(b.quantity, 0) from product_variants b where b.id = l.variant_id) as on_hand "
            + "from catering_plan_lines l left join catering_usage u on u.event_id = l.event_id and u.variant_id = l.variant_id where l.event_id = ? "
            + "group by l.variant_id, l.item_name, l.unit, l.planned_qty, l.unit_cost order by l.item_name",
            (rs, i) -> {
                BigDecimal issued = rs.getBigDecimal("issued"), extra = rs.getBigDecimal("extra"), ret = rs.getBigDecimal("returned"), waste = rs.getBigDecimal("wasted");
                return new PlanDto(rs.getObject("variant_id", UUID.class), rs.getString("item_name"), rs.getString("unit"), rs.getBigDecimal("planned_qty"), issued, extra, ret, waste,
                    issued.add(extra).subtract(ret), rs.getBigDecimal("on_hand"), money ? rs.getBigDecimal("unit_cost") : null);
            }, e.id());
    }

    /** Revenue, cost and profit for one event, all from its own rows. */
    private Summary summary(UUID bid, Ev e) {
        BigDecimal got = received(e.id());
        // A cancelled event only earns what the customer did not get back (for example a kept deposit).
        BigDecimal revenue = "CANCELLED".equals(e.status()) ? got : e.total();
        BigDecimal estIng = jdbc.queryForObject("select coalesce(sum(planned_qty * unit_cost), 0) from catering_plan_lines where event_id = ?", BigDecimal.class, e.id());
        Integer hasPlan = jdbc.queryForObject("select count(*) from catering_plan_lines where event_id = ?", Integer.class, e.id());
        if (hasPlan == null || hasPlan == 0) {
            estIng = BigDecimal.ZERO;
            Map<UUID, BigDecimal> need = catalog.requirements(bid, e.packageId(), e.guests());
            if (!need.isEmpty()) for (ProductVariant v : variants.findAllById(need.keySet())) estIng = estIng.add(v.getCost().multiply(need.get(v.getId())));
        }
        BigDecimal estOther = sum("select coalesce(sum(amount), 0) from catering_costs where event_id = ? and estimated = true", e.id());
        BigDecimal actIng = sum("select coalesce(sum(case when kind = 'RETURN' then -quantity * unit_cost else quantity * unit_cost end), 0) from catering_usage where event_id = ?", e.id());
        BigDecimal actOther = sum("select coalesce(sum(c.amount), 0) from catering_costs c left join expenses x on x.id = c.expense_id where c.event_id = ? and c.estimated = false and coalesce(x.status, 'APPROVED') <> 'REJECTED'", e.id());
        BigDecimal estCost = Money.scale(estIng.add(estOther)), actCost = Money.scale(actIng.add(actOther));
        BigDecimal gross = Money.scale(revenue.subtract(actCost));
        BigDecimal margin = revenue.signum() == 0 ? BigDecimal.ZERO : gross.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
        BigDecimal balance = "CANCELLED".equals(e.status()) ? BigDecimal.ZERO : e.total().subtract(got).max(BigDecimal.ZERO);
        return new Summary(Money.scale(revenue), got, balance, Money.scale(estIng), Money.scale(estOther), estCost, Money.scale(actIng), Money.scale(actOther), actCost,
            Money.scale(revenue.subtract(estCost)), gross, margin);
    }

    private BigDecimal sum(String sql, UUID id) { BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, id); return v == null ? BigDecimal.ZERO : v; }

    private static String name(ProductVariant v) { return v.getProduct().getName() + (v.getLabel() == null || v.getLabel().isBlank() ? "" : " (" + v.getLabel() + ")"); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
