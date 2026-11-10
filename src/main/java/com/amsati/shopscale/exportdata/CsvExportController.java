package com.amsati.shopscale.exportdata;

import com.amsati.shopscale.audit.AuditService;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import com.amsati.shopscale.subscription.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Plain CSV downloads of the business's own records. Always scoped to the signed-in business. Included on every plan (DATA_EXPORT). */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class CsvExportController {
    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final AuditService audit;

    private static final Map<String, String[]> SQL = Map.of(
        "products", new String[] {
            "select p.name as product, v.label as option, v.sku, v.barcode, v.price, v.cost, v.quantity as stock, v.active, p.status from product_variants v join products p on p.id = v.product_id where v.business_id = ? order by p.name, v.label"},
        "customers", new String[] {
            "select name, phone, email, address, balance as amount_owed, created_at from customers where business_id = ? order by name"},
        "sales", new String[] {
            "select sale_number, created_at, status, subtotal, discount, total, amount_paid, amount_owed, note from sales where business_id = ? order by sale_number"},
        "expenses", new String[] {
            "select expense_date, category, amount, payment_method, description from expenses where business_id = ? order by expense_date, created_at"});

    @GetMapping("/{what}.csv")
    @PreAuthorize("@access.has('DATA_EXPORT')")
    public ResponseEntity<byte[]> export(@PathVariable String what) {
        UUID bid = Tenant.businessId();
        subscriptions.requireFeature(bid, "DATA_EXPORT", "Data export");
        String[] q = SQL.get(what);
        if (q == null) throw ApiException.notFound("Unknown export");
        List<Map<String, Object>> rows = jdbc.queryForList(q[0], bid);
        StringBuilder sb = new StringBuilder("﻿");   // BOM so Excel reads UTF-8 names correctly
        if (rows.isEmpty()) {
            sb.append("No records\n");
        } else {
            sb.append(String.join(",", rows.get(0).keySet())).append('\n');
            for (Map<String, Object> r : rows) {
                boolean first = true;
                for (Object v : r.values()) { if (!first) sb.append(','); first = false; sb.append(cell(v)); }
                sb.append('\n');
            }
        }
        audit.record(bid, Tenant.userId(), "DATA_EXPORTED", "export", bid, what);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shopscale-" + what + ".csv\"")
            .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quotes the value and neutralises spreadsheet formulas (= + - @) so a customer name cannot run code in Excel. */
    static String cell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
