package com.amsati.shopscale.industry;

import com.amsati.shopscale.business.BusinessType;
import java.util.EnumSet;
import java.util.Set;

/** Operational modules a business can use. Which ones are on is decided by the business type chosen at sign-up. */
public enum Workflow {
    RETAIL, WHOLESALE, RESTAURANT, CATERING, PRODUCTION, ONLINE;

    public static Set<Workflow> forType(BusinessType t) {
        if (t == null) return EnumSet.of(RETAIL);
        return switch (t) {
            case RETAIL -> EnumSet.of(RETAIL, ONLINE);
            case WHOLESALE -> EnumSet.of(RETAIL, WHOLESALE, ONLINE);
            case RESTAURANT -> EnumSet.of(RESTAURANT, RETAIL, ONLINE);
            case CATERING, SMALL_CHOPS -> EnumSet.of(CATERING, PRODUCTION, RETAIL, ONLINE);
            case PRODUCTION -> EnumSet.of(PRODUCTION, WHOLESALE, RETAIL, ONLINE);
            // Livestock is recorded as the owner's choice. Only the general tools are active for it today.
            case LIVESTOCK -> EnumSet.of(RETAIL);
        };
    }
}
