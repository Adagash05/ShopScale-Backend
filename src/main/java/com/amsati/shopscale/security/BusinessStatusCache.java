package com.amsati.shopscale.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Whether a business is suspended, cached for a few seconds so the check does not hit the database on every request. */
@Component
public class BusinessStatusCache {
    private static final long TTL_MS = 15_000;
    private record Entry(boolean suspended, long at) {}
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public BusinessStatusCache(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean suspended(UUID businessId) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(businessId);
        if (e != null && now - e.at() < TTL_MS) return e.suspended();
        boolean s = Boolean.TRUE.equals(jdbc.query("select status from businesses where id = ?", rs -> rs.next() && "SUSPENDED".equals(rs.getString(1)), businessId));
        cache.put(businessId, new Entry(s, now));
        return s;
    }

    /** Called when a Super Admin changes a business's status, so this instance reacts at once (other instances within the TTL). */
    public void evict(UUID businessId) { cache.remove(businessId); }
}
