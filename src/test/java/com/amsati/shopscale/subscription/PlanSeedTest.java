package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

/** Reads the V15 migration (the final approved configuration) and checks the numbers and feature split. */
class PlanSeedTest {
    private static String sql() throws Exception {
        try (var in = PlanSeedTest.class.getResourceAsStream("/db/migration/V15__final_pricing_limits_features.sql")) { return new String(in.readAllBytes()); }
    }

    private static String update(String code) throws Exception {
        Matcher m = Pattern.compile("UPDATE plans SET (monthly_price[^;]*?) WHERE code = '" + code + "';").matcher(sql());
        assertTrue(m.find(), code);
        return m.group(1).replaceAll("\\s+", " ");
    }

    @Test void freePlan() throws Exception {
        String u = update("FREE");
        assertTrue(u.contains("monthly_price = 0,") && u.contains("annual_price = 0,"));
        assertTrue(u.contains("max_users = 1,") && u.contains("max_locations = 1,") && u.contains("max_items = 15,") && u.contains("max_customers = 15,") && u.contains("max_expenses = 15,") && u.contains("max_suppliers = 5,"));
    }
    @Test void essentialPlan() throws Exception {
        String u = update("ESSENTIAL");
        assertTrue(u.contains("monthly_price = 4999.00") && u.contains("annual_price = 49990.00") && u.contains("max_users = 2,") && u.contains("max_locations = 1,"));
        assertTrue(u.contains("max_items = 100,") && u.contains("max_customers = 100,") && u.contains("max_expenses = 100,") && u.contains("max_suppliers = 20,"));
    }
    @Test void growthPlanIsRecommended() throws Exception {
        String u = update("GROWTH");
        assertTrue(u.contains("monthly_price = 11999.00") && u.contains("annual_price = 119990.00") && u.contains("max_users = 5,") && u.contains("max_locations = 2,"));
        assertTrue(u.contains("max_items = 500,") && u.contains("max_customers = NULL,") && u.contains("max_expenses = NULL,") && u.contains("max_suppliers = 50,"));
        assertTrue(u.contains("recommended = true"));
        assertFalse(update("BUSINESS").contains("recommended = true"));
    }
    @Test void businessPlanUnlimitedIsNull() throws Exception {
        String u = update("BUSINESS");
        assertTrue(u.contains("monthly_price = 24999.00") && u.contains("annual_price = 249990.00") && u.contains("max_users = 10,") && u.contains("max_locations = 5,"));
        assertTrue(u.contains("max_items = NULL,") && u.contains("max_customers = NULL,") && u.contains("max_expenses = NULL,") && u.contains("max_suppliers = NULL,"));
    }
    @Test void starterIsHiddenNotDeleted() throws Exception {
        assertTrue(sql().contains("UPDATE plans SET active = false WHERE code = 'STARTER'"));
        assertFalse(sql().contains("DELETE FROM plans"));
    }
    @Test void freeHasNoPaidFeatures() throws Exception {
        String s = sql();
        // The Free plan only receives features from the first INSERT, which targets all four plans and lists the basics.
        String basics = s.substring(s.indexOf("ARRAY['POS'"), s.indexOf("]", s.indexOf("ARRAY['POS'")));
        for (String banned : new String[] {"DATA_EXPORT", "PURCHASING", "SUPPLIER_BALANCES", "STOREFRONT", "ONLINE_ORDERS", "BRANCH_TRANSFERS", "STAFF_PERMISSIONS", "CUSTOM_DOMAINS"}) assertFalse(basics.contains(banned), banned);
        // and no later INSERT names FREE
        assertFalse(s.substring(s.indexOf("-- Essential and above")).contains("'FREE'"));
    }
    @Test void storefrontStartsAtGrowth() throws Exception {
        String s = sql();
        String growthUp = s.substring(s.indexOf("-- Growth and above"), s.indexOf("-- Business only"));
        assertTrue(growthUp.contains("'STOREFRONT'") && growthUp.contains("'ONLINE_ORDERS'") && growthUp.contains("IN ('GROWTH','BUSINESS')"));
        assertFalse(s.substring(s.indexOf("-- Essential and above"), s.indexOf("-- Growth and above")).contains("STOREFRONT"));
        assertTrue(s.substring(s.indexOf("-- Business only")).contains("'CUSTOM_DOMAINS'"));
    }
}
