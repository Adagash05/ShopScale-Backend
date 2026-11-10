package com.amsati.shopscale.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementDto(UUID id, UUID variantId, String productName, String variantLabel, MovementType type,
                          BigDecimal quantityChange, BigDecimal balanceAfter, String note, Instant createdAt) {}
