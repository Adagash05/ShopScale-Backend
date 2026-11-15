package com.amsati.shopscale.superadmin;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Support tickets. Merchants raise and follow tickets for their own business (/api/support, tenant taken from the token);
 * the Super Admin works them in the console (/api/super-admin/support). Internal notes are never returned to merchants.
 * File attachments are not supported.
 */
@RestController
public class SupportController {
    private static final String CATS = "BILLING|ACCOUNT|STORE|PAYMENTS|INVENTORY|SALES|BUG|OTHER";
    private static final String PRIOS = "LOW|NORMAL|HIGH|URGENT";
    private static final String STATUSES = "OPEN|IN_PROGRESS|WAITING_FOR_CUSTOMER|RESOLVED|CLOSED";
    private static final Map<String, String> SORT = new LinkedHashMap<>();
    static { SORT.put("updated", "t.updated_at"); SORT.put("created", "t.created_at"); SORT.put("priority", "case t.priority when 'URGENT' then 4 when 'HIGH' then 3 when 'NORMAL' then 2 else 1 end"); SORT.put("status", "t.status"); }

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final SuperAdminAccess access;

    public SupportController(JdbcTemplate jdbc, AdminAudit audit, SuperAdminAccess access) { this.jdbc = jdbc; this.audit = audit; this.access = access; }

    public record NewTicket(@NotBlank @Size(min = 3, max = 200) String subject, @NotBlank @Size(min = 10, max = 4000) String description,
                            @Pattern(regexp = CATS) String category, @Pattern(regexp = PRIOS) String priority) {}
    public record Reply(@NotBlank @Size(max = 4000) String body) {}

    // ---------------- merchant side ----------------
    @PostMapping("/api/support/tickets")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> raise(@Valid @RequestBody NewTicket r) {
        UUID bid = Tenant.businessId();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into support_tickets (id, business_id, user_id, category, priority, subject, description) values (?,?,?,?,?,?,?)",
            id, bid, Tenant.userId(), r.category() == null ? "OTHER" : r.category(), r.priority() == null ? "NORMAL" : r.priority(), r.subject().trim(), r.description().trim());
        return Map.of("id", id, "status", "OPEN");
    }

    @GetMapping("/api/support/tickets")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> mine() {
        return AdminSql.rows(jdbc, "select id, category, priority, status, subject, resolution, created_at, updated_at, resolved_at from support_tickets where business_id = ? order by updated_at desc limit 100", Tenant.businessId());
    }

    @GetMapping("/api/support/tickets/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> mineOne(@PathVariable UUID id) {
        Map<String, Object> t = AdminSql.one(jdbc, "select id, category, priority, status, subject, description, resolution, created_at, updated_at, resolved_at from support_tickets where id = ? and business_id = ?", id, Tenant.businessId());
        if (t == null) throw ApiException.notFound("Ticket not found");
        return Map.of("ticket", t, "notes", AdminSql.rows(jdbc, "select n.id, n.body, n.created_at, (n.author_id = ?) as from_you from support_ticket_notes n where n.ticket_id = ? and not n.internal order by n.created_at", Tenant.userId(), id));
    }

    @PostMapping("/api/support/tickets/{id}/reply")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> reply(@PathVariable UUID id, @Valid @RequestBody Reply r) {
        String status = jdbc.query("select status from support_tickets where id = ? and business_id = ? for update", rs -> rs.next() ? rs.getString(1) : null, id, Tenant.businessId());
        if (status == null) throw ApiException.notFound("Ticket not found");
        if ("CLOSED".equals(status)) throw ApiException.conflict("This ticket is closed. Open a new one.");
        jdbc.update("insert into support_ticket_notes (id, ticket_id, author_id, internal, body) values (?,?,?,false,?)", UUID.randomUUID(), id, Tenant.userId(), r.body().trim());
        jdbc.update("update support_tickets set updated_at = now(), status = case when status in ('WAITING_FOR_CUSTOMER','RESOLVED') then 'OPEN' else status end, resolved_at = case when status in ('WAITING_FOR_CUSTOMER','RESOLVED') then null else resolved_at end where id = ?", id);
        return Map.of("ok", true);
    }

    // ---------------- Super Admin side ----------------
    @GetMapping("/api/super-admin/support/tickets")
    @PreAuthorize("@superAdmin.ok()")
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String priority,
                                    @RequestParam(required = false) String category, @RequestParam(required = false) String assigned, @RequestParam(required = false) UUID businessId,
                                    @RequestParam(defaultValue = "updated") String sort, @RequestParam(defaultValue = "desc") String dir,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        AdminSql.Where w = new AdminSql.Where();
        if (q != null && !q.isBlank()) { String l = AdminSql.like(q); w.and("(lower(t.subject) like ? escape '\\' or lower(coalesce(b.name,'')) like ? escape '\\' or lower(coalesce(u.email,'')) like ? escape '\\')", l, l, l); }
        if ("open".equalsIgnoreCase(status)) w.and("t.status in ('OPEN','IN_PROGRESS','WAITING_FOR_CUSTOMER')"); else w.andIf(status, "t.status = ?", status == null ? null : status.toUpperCase());
        w.andIf(priority, "t.priority = ?", priority == null ? null : priority.toUpperCase());
        w.andIf(category, "t.category = ?", category == null ? null : category.toUpperCase());
        if ("me".equalsIgnoreCase(assigned)) w.and("t.assigned_to = ?", access.current().userId()); else if ("unassigned".equalsIgnoreCase(assigned)) w.and("t.assigned_to is null");
        if (businessId != null) w.and("t.business_id = ?", businessId);
        String cols = "t.id, t.business_id, b.name as business_name, t.user_id, u.full_name as user_name, u.email as user_email, t.category, t.priority, t.status, t.subject, t.assigned_to, t.created_at, t.updated_at, t.resolved_at";
        return AdminSql.page(jdbc, cols, "support_tickets t left join businesses b on b.id = t.business_id left join users u on u.id = t.user_id", w, AdminSql.order(SORT, sort, dir), page, size);
    }

    @GetMapping("/api/super-admin/support/tickets/{id}")
    @PreAuthorize("@superAdmin.ok()")
    public Map<String, Object> one(@PathVariable UUID id) {
        Map<String, Object> t = AdminSql.one(jdbc, "select t.*, b.name as business_name, u.full_name as user_name, u.email as user_email, a.email as assigned_email from support_tickets t left join businesses b on b.id = t.business_id left join users u on u.id = t.user_id left join users a on a.id = t.assigned_to where t.id = ?", id);
        if (t == null) throw ApiException.notFound("Ticket not found");
        return Map.of("ticket", t, "notes", AdminSql.rows(jdbc, "select n.id, n.body, n.internal, n.created_at, u.full_name as author_name, u.email as author_email, (u.platform_role is not null) as by_admin from support_ticket_notes n left join users u on u.id = n.author_id where n.ticket_id = ? order by n.created_at", id));
    }

    public record AdminNewTicket(UUID businessId, UUID userId, @NotBlank @Size(min = 3, max = 200) String subject, @NotBlank @Size(min = 10, max = 4000) String description,
                                 @Pattern(regexp = CATS) String category, @Pattern(regexp = PRIOS) String priority) {}

    @PostMapping("/api/super-admin/support/tickets")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody AdminNewTicket r) {
        if (r.businessId() != null && count("select count(*) from businesses where id = ?", r.businessId()) == 0) throw ApiException.badRequest("That business does not exist");
        if (r.userId() != null && count("select count(*) from users where id = ?", r.userId()) == 0) throw ApiException.badRequest("That user does not exist");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into support_tickets (id, business_id, user_id, category, priority, subject, description, assigned_to) values (?,?,?,?,?,?,?,?)",
            id, r.businessId(), r.userId(), r.category() == null ? "OTHER" : r.category(), r.priority() == null ? "NORMAL" : r.priority(), r.subject().trim(), r.description().trim(), access.current().userId());
        audit.ok("SUPPORT_TICKET_CREATED", "ticket", id, r.businessId(), null, null);
        return Map.of("id", id);
    }

    public record Update(@Pattern(regexp = STATUSES) String status, @Pattern(regexp = PRIOS) String priority, Boolean assignToMe, @Size(max = 2000) String resolution) {}

    @PutMapping("/api/super-admin/support/tickets/{id}")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody Update r) {
        Map<String, Object> t = AdminSql.one(jdbc, "select status, priority, assigned_to, resolution, business_id from support_tickets where id = ? for update", id);
        if (t == null) throw ApiException.notFound("Ticket not found");
        String status = r.status() == null ? (String) t.get("status") : r.status();
        String resolution = r.resolution() != null ? r.resolution().trim() : (String) t.get("resolution");
        boolean closing = status.equals("RESOLVED") || status.equals("CLOSED");
        if (closing && (resolution == null || resolution.isBlank())) throw ApiException.badRequest("Write the resolution before resolving or closing a ticket");
        Object assignee = Boolean.TRUE.equals(r.assignToMe()) ? access.current().userId() : t.get("assignedTo") == null ? null : UUID.fromString((String) t.get("assignedTo"));
        jdbc.update("update support_tickets set status = ?, priority = ?, assigned_to = ?, resolution = ?, resolved_at = case when ? then coalesce(resolved_at, now()) else null end, updated_at = now() where id = ?",
            status, r.priority() == null ? t.get("priority") : r.priority(), assignee, resolution == null || resolution.isBlank() ? null : resolution, closing, id);
        UUID bid = t.get("businessId") == null ? null : UUID.fromString((String) t.get("businessId"));
        audit.ok("SUPPORT_TICKET_UPDATED", "ticket", id, bid, null, Map.of("statusBefore", String.valueOf(t.get("status")), "statusAfter", status));
        return Map.of("ok", true, "status", status);
    }

    public record AdminNote(@NotBlank @Size(max = 4000) String body, Boolean internal) {}

    @PostMapping("/api/super-admin/support/tickets/{id}/notes")
    @PreAuthorize("@superAdmin.ok()")
    @Transactional
    public Map<String, Object> note(@PathVariable UUID id, @Valid @RequestBody AdminNote r) {
        if (count("select count(*) from support_tickets where id = ?", id) == 0) throw ApiException.notFound("Ticket not found");
        boolean internal = !Boolean.FALSE.equals(r.internal());   // internal unless the admin explicitly writes to the merchant
        jdbc.update("insert into support_ticket_notes (id, ticket_id, author_id, internal, body) values (?,?,?,?,?)", UUID.randomUUID(), id, access.current().userId(), internal, r.body().trim());
        jdbc.update("update support_tickets set updated_at = now() where id = ?", id);
        audit.ok(internal ? "SUPPORT_NOTE_ADDED" : "SUPPORT_REPLY_SENT", "ticket", id, null, null, null);
        return Map.of("ok", true);
    }

    private int count(String sql, Object arg) { Integer c = jdbc.queryForObject(sql, Integer.class, arg); return c == null ? 0 : c; }
}
