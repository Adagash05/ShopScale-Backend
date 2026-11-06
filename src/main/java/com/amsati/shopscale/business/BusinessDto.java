package com.amsati.shopscale.business;

import java.util.UUID;

public record BusinessDto(UUID id, String name, String slug, BusinessType businessType, String subtype,
                          String description, String phone, String email, String address,
                          String currency, String timezone, boolean allowNegativeStock) {

    public static BusinessDto of(Business b) {
        return new BusinessDto(b.getId(), b.getName(), b.getSlug(), b.getBusinessType(), b.getSubtype(),
            b.getDescription(), b.getPhone(), b.getEmail(), b.getAddress(), b.getCurrency(), b.getTimezone(),
            b.isAllowNegativeStock());
    }
}
