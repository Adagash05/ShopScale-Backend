package com.amsati.shopscale.inventory;

import java.util.UUID;

public record LowStockDto(UUID variantId, String productName, String variantLabel, int quantity, int lowStockThreshold) {}
