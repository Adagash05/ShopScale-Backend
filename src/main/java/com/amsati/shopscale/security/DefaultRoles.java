package com.amsati.shopscale.security;

import static com.amsati.shopscale.security.Permission.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Safe default permissions for the built-in roles. They are code, not data, so they cannot be edited by accident;
 * to customise one, an owner creates a custom role (the team screen can copy a default as a starting point).
 */
public final class DefaultRoles {
    public record Def(Role role, String name, String description, Set<Permission> permissions) {}

    private static final Map<Role, Def> DEFS = new EnumMap<>(Role.class);

    private static Set<Permission> set(Permission... ps) {
        return Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(ps)));
    }

    private static Set<Permission> allExcept(Permission... excluded) {
        EnumSet<Permission> s = EnumSet.allOf(Permission.class);
        s.removeAll(Arrays.asList(excluded));
        return Collections.unmodifiableSet(s);
    }

    private static void def(Role r, String name, String description, Set<Permission> perms) {
        DEFS.put(r, new Def(r, name, description, perms));
    }

    static {
        def(Role.OWNER, "Owner", "Full control of the business, including billing and team.", Collections.unmodifiableSet(EnumSet.allOf(Permission.class)));

        def(Role.ADMINISTRATOR, "Administrator", "Runs the business day to day and manages the team. Cannot change billing or the payment connection.",
            allExcept(SUBSCRIPTION_MANAGE, BILLING_MANAGE, STORE_PAYMENTS_MANAGE, INTEGRATIONS_MANAGE));

        def(Role.MANAGER, "Manager", "Looks after sales, stock, customers and orders. Does not see profit or expenses by default.",
            set(PRICING_MANAGE, STOCK_TRANSFER, STOCK_WASTAGE, SALES_VOID, QUOTES_MANAGE, TABLES_MANAGE, PRODUCTION_MANAGE, CATERING_VIEW, CATERING_MANAGE, SHIFTS_MANAGE, SHIFTS_RECONCILE, ACTIVITY_VIEW, DATA_IMPORT, DATA_EXPORT, LOCATIONS_MANAGE,
                DASHBOARD_VIEW, DASHBOARD_OPERATIONAL,
                PRODUCTS_VIEW, PRODUCTS_CREATE, PRODUCTS_EDIT, PRODUCTS_ARCHIVE, CATEGORIES_MANAGE, VARIANTS_MANAGE, MODIFIERS_MANAGE,
                STOCK_VIEW, STOCK_RESTOCK, STOCK_ADJUST, STOCK_MOVEMENTS_VIEW, INGREDIENTS_MANAGE, RECIPES_MANAGE,
                SALES_VIEW, SALES_CREATE, SALES_DISCOUNT, SALES_REFUND, SALES_HISTORY_VIEW, RECEIPTS_GENERATE,
                CUSTOMERS_VIEW, CUSTOMERS_CREATE, CUSTOMERS_EDIT, CUSTOMER_BALANCES_VIEW, REPAYMENTS_RECORD,
                ORDERS_VIEW, ORDERS_CREATE, ORDERS_EDIT, ORDERS_ACCEPT, ORDERS_CANCEL, ORDERS_STATUS_UPDATE, DELIVERY_MANAGE,
                KITCHEN_VIEW, KITCHEN_STATUS_UPDATE, KITCHEN_MARK_READY,
                STORE_VIEW, STORE_ORDERS_MANAGE, REPORTS_SALES, REPORTS_INVENTORY, REPORTS_CUSTOMERS,
                SUPPLIERS_VIEW, SUPPLIERS_MANAGE, PURCHASES_VIEW, PURCHASES_CREATE,
                SETTINGS_VIEW, TEAM_VIEW));

        def(Role.SALES_STAFF, "Cashier", "Records sales and prints receipts. Sees only their own sales.",
            set(SHIFTS_MANAGE, QUOTES_MANAGE, TABLES_MANAGE, PRODUCTS_VIEW, STOCK_VIEW, SALES_CREATE, SALES_HISTORY_VIEW, RECEIPTS_GENERATE,
                CUSTOMERS_VIEW, CUSTOMERS_CREATE));

        def(Role.INVENTORY_STAFF, "Storekeeper", "Adds products and looks after stock.",
            set(STOCK_TRANSFER, STOCK_WASTAGE, PRODUCTION_MANAGE, PRODUCTS_VIEW, PRODUCTS_CREATE, PRODUCTS_EDIT, CATEGORIES_MANAGE, VARIANTS_MANAGE,
                STOCK_VIEW, STOCK_RESTOCK, STOCK_ADJUST, STOCK_MOVEMENTS_VIEW, INGREDIENTS_MANAGE, RECIPES_MANAGE,
                SUPPLIERS_VIEW, PURCHASES_VIEW, PURCHASES_CREATE, REPORTS_INVENTORY));

        def(Role.KITCHEN_STAFF, "Restaurant / kitchen staff", "Sees kitchen orders and updates preparation. Sees no money information.",
            set(STOCK_WASTAGE, CATERING_VIEW, KITCHEN_VIEW, KITCHEN_STATUS_UPDATE, KITCHEN_MARK_READY));

        def(Role.ACCOUNTANT, "Accountant", "Sees sales, expenses and what customers owe, and can record expenses and repayments.",
            set(DASHBOARD_VIEW, DASHBOARD_FINANCIAL, SALES_VIEW, SALES_HISTORY_VIEW, CUSTOMERS_VIEW, CUSTOMER_BALANCES_VIEW, REPAYMENTS_RECORD,
                FINANCE_OVERVIEW, EXPENSES_VIEW, EXPENSES_CREATE, PAYMENTS_VIEW, FINANCE_REPORTS_VIEW, FINANCE_EXPORT,
                SUPPLIERS_VIEW, PURCHASES_VIEW, SUPPLIER_PAYMENTS_RECORD, CASH_ACCOUNTS_MANAGE, SHIFTS_RECONCILE, EXPENSES_APPROVE, ACTIVITY_VIEW, DATA_EXPORT,
                REPORTS_SALES, REPORTS_FINANCIAL, REPORTS_EXPORT));
    }

    private DefaultRoles() {}

    public static Def of(Role r) { return DEFS.get(r); }

    public static Collection<Def> all() { return DEFS.values(); }

    public static Set<Permission> permissions(Role r) {
        Def d = DEFS.get(r);
        return d == null ? Set.of() : d.permissions();
    }

    /** Privilege-escalation rule: you can only hand out access you hold yourself. */
    public static boolean canGrant(Set<Permission> callerHas, Set<Permission> target) {
        return callerHas.containsAll(target);
    }
}
