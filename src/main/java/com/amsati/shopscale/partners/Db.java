package com.amsati.shopscale.partners;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Small helpers for the JDBC code in this package (timestamps and UUIDs the way PostgreSQL's driver wants them). */
final class Db {
    private Db() {}

    static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static UUID uuid(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
    }

    /** Escapes LIKE wildcards in user text. */
    static String like(String s) { return "%" + s.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
}
