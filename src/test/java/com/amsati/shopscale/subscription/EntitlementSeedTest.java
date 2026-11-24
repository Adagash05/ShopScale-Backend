package com.amsati.shopscale.subscription;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V31 is the one place the revised allowances live. These checks read the migration text; the real values are proven against PostgreSQL by scripts/db-checks. */
class EntitlementSeedTest {
    static String sql() throws Exception { return Files.readString(Path.of("src/main/resources/db/migration/V31__plan_entitlements_revision.sql")); }

    @Test void limitsMatchTheDirective() throws Exception {
        String s = sql();
        assertTrue(s.contains("max_users = 1,  max_items = 10,   max_customers = 10,   max_expenses = 10,   max_suppliers = 0,    max_whatsapp_reminders = 0,    expense_period = 'TOTAL' WHERE code = 'FREE'"));
        assertTrue(s.contains("max_users = 2,  max_items = 50,   max_customers = 50,   max_expenses = 50,   max_suppliers = 10,   max_whatsapp_reminders = 20,   expense_period = 'MONTH' WHERE code = 'ESSENTIAL'"));
        assertTrue(s.contains("max_users = 5,  max_items = 200,  max_customers = NULL, max_expenses = NULL, max_suppliers = 50,   max_whatsapp_reminders = 50,   expense_period = 'TOTAL' WHERE code = 'GROWTH'"));
        assertTrue(s.contains("max_users = 10, max_items = NULL, max_customers = NULL, max_expenses = NULL, max_suppliers = NULL, max_whatsapp_reminders = NULL, expense_period = 'TOTAL' WHERE code = 'BUSINESS'"));
    }
    @Test void exportAndAskLeaveEssentialAndGrowthGainsThem() throws Exception {
        String s = sql();
        assertTrue(s.contains("DELETE FROM plan_features WHERE plan_code = 'ESSENTIAL' AND feature IN ('DATA_EXPORT', 'ASK_SHOPSCALE')"));
        assertTrue(s.contains("'DATA_EXPORT', 'ASK_SHOPSCALE', 'BEST_SELLING_PRODUCTS', 'BEST_SELLING_CUSTOMERS'"));
        assertTrue(s.contains("WHERE p.code IN ('GROWTH', 'BUSINESS')"));
    }
    @Test void pricesAndSubscriptionsAreNotTouched() throws Exception {
        String s = sql().toLowerCase();
        assertFalse(s.contains("monthly_price"));
        assertFalse(s.contains("annual_price"));
        assertFalse(s.contains("update subscriptions"));
        assertFalse(s.contains("delete from products"));
        assertFalse(s.contains("delete from customers"));
    }
}
