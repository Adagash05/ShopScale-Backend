package com.amsati.shopscale.partners;

import java.math.BigDecimal;

/** The programme's current settings. Referrals and commissions copy the terms that applied when they were created. */
public record Programme(BigDecimal commissionRate, int commissionMonths, int attributionDays, int holdingDays,
                        BigDecimal minWithdrawal, boolean active, String termsVersion) {}
