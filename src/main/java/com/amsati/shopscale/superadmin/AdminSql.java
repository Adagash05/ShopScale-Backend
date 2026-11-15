package com.amsati.shopscale.superadmin;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared plumbing for the Super Admin list endpoints: server-side pagination, whitelisted sorting, camelCase rows, safe LIKE text. */
final class AdminSql {
    private AdminSql() {}

    static String like(String s) { return "%" + s.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }

    static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) { if (c == '_') up = true; else { b.append(up ? Character.toUpperCase(c) : c); up = false; } }
        return b.toString();
    }

    static Map<String, Object> row(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Timestamp t) v = t.toInstant().toString();
            else if (v instanceof java.sql.Date d) v = d.toString();
            else if (v instanceof UUID u) v = u.toString();
            out.put(camel(e.getKey()), v);
        }
        return out;
    }

    static List<Map<String, Object>> rows(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList(sql, args)) out.add(row(m));
        return out;
    }

    static Map<String, Object> one(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> l = rows(jdbc, sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Builds a WHERE clause piece by piece with positional arguments. */
    static final class Where {
        final StringBuilder sql = new StringBuilder(" where 1=1");
        final List<Object> args = new ArrayList<>();
        Where and(String clause, Object... a) { sql.append(" and ").append(clause); args.addAll(Arrays.asList(a)); return this; }
        Where andIf(Object value, String clause, Object... a) { return value == null || (value instanceof String s && s.isBlank()) ? this : and(clause, a); }
    }

    static int size(int requested) { return requested < 1 ? 25 : Math.min(requested, 100); }
    static int page(int requested) { return Math.max(requested, 0); }

    /**
     * One page plus the total. {@code orderBy} must come from a server-side whitelist, never from the request.
     * {@code from} is the FROM/JOIN text; {@code select} the column list.
     */
    static Map<String, Object> page(JdbcTemplate jdbc, String select, String from, Where w, String orderBy, int page, int size) {
        int p = page(page), s = size(size);
        Long total = jdbc.queryForObject("select count(*) from " + from + w.sql, Long.class, w.args.toArray());
        List<Object> a = new ArrayList<>(w.args);
        a.add(s); a.add((long) p * s);
        List<Map<String, Object>> items = rows(jdbc, "select " + select + " from " + from + w.sql + " order by " + orderBy + " limit ? offset ?", a.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        long t = total == null ? 0 : total;
        out.put("items", items); out.put("page", p); out.put("size", s); out.put("total", t); out.put("totalPages", (t + s - 1) / s);
        return out;
    }

    /** Picks a whitelisted ORDER BY, defaulting to the first entry; direction is only ever asc or desc. */
    static String order(Map<String, String> allowed, String sort, String dir) {
        String col = allowed.getOrDefault(sort == null ? "" : sort, allowed.values().iterator().next());
        return col + ("asc".equalsIgnoreCase(dir) ? " asc" : " desc") + ", 1";
    }
}
