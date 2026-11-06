package com.amsati.shopscale.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseSummary(UUID id, long purchaseNumber, String supplierName, String reference, BigDecimal total,
                              BigDecimal amountPaid, BigDecimal amountOwed, Instant createdAt) {}
