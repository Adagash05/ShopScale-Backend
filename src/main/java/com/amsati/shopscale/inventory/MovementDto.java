package com.amsati.shopscale.inventory;

import java.time.Instant;
import java.util.UUID;

public record MovementDto(UUID id, UUID variantId, String productName, String variantLabel, MovementType type,
                          int quantityChange, int balanceAfter, String note, Instant createdAt) {}
