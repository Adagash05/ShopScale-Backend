package com.amsati.shopscale.partners;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Programme settings: one row, changed only by an administrator, every change audited. Changes never touch commissions already earned. */
@Service
@RequiredArgsConstructor
public class PartnerSettingsService {
    private final JdbcTemplate jdbc;
    private final PartnerAudit audit;

    public record UpdateRequest(
        @NotNull @DecimalMin("0.0001") @DecimalMax("1") BigDecimal commissionRate,
        @NotNull @Min(1) @Max(60) Integer commissionMonths,
        @NotNull @Min(1) @Max(365) Integer attributionDays,
        @NotNull @Min(0) @Max(120) Integer holdingDays,
        @NotNull @DecimalMin("0") BigDecimal minWithdrawal,
        @NotNull Boolean active) {}

    public Programme get() {
        return jdbc.queryForObject("select commission_rate, commission_months, attribution_days, holding_days, min_withdrawal, active, terms_version from partner_settings where id = 1",
            (rs, i) -> new Programme(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBigDecimal(5), rs.getBoolean(6), rs.getString(7)));
    }

    @Transactional
    public Programme update(UpdateRequest r) {
        Programme before = get();
        BigDecimal rate = r.commissionRate().setScale(4, java.math.RoundingMode.HALF_UP);
        if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) throw ApiException.badRequest("The commission rate must be between 0.01% and 100%");
        jdbc.update("update partner_settings set commission_rate=?, commission_months=?, attribution_days=?, holding_days=?, min_withdrawal=?, active=?, updated_by=?, updated_at=now() where id=1",
            rate, r.commissionMonths(), r.attributionDays(), r.holdingDays(), r.minWithdrawal().setScale(2, java.math.RoundingMode.HALF_UP), r.active(), Tenant.userId());
        audit.record("SETTINGS_CHANGED", "settings", null, "rate " + before.commissionRate() + "->" + rate + ", months " + before.commissionMonths() + "->" + r.commissionMonths()
            + ", attribution days " + before.attributionDays() + "->" + r.attributionDays() + ", holding days " + before.holdingDays() + "->" + r.holdingDays()
            + ", minimum " + before.minWithdrawal() + "->" + r.minWithdrawal() + ", active " + before.active() + "->" + r.active());
        return get();
    }

    /** What visitors may see before signing in. */
    public Map<String, Object> publicView() {
        Programme p = get();
        return Map.of("name", "ShopScale Partners", "commissionRate", p.commissionRate(), "commissionMonths", p.commissionMonths(),
            "attributionDays", p.attributionDays(), "holdingDays", p.holdingDays(), "minWithdrawal", p.minWithdrawal(), "currency", "NGN", "active", p.active());
    }
}
