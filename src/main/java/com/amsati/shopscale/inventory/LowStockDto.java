package com.amsati.shopscale.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record LowStockDto(UUID variantId, String productName, String variantLabel, BigDecimal quantity, BigDecimal lowStockThreshold) {}
