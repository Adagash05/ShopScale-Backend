package com.amsati.shopscale.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleSummary(UUID id, long saleNumber, SaleStatus status, String customerName, BigDecimal total,
                          BigDecimal amountPaid, BigDecimal amountOwed, Instant createdAt) {}
