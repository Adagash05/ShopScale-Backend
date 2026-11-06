package com.amsati.shopscale.security;

/** The single permission catalogue. Add new permissions here, then reference them with @access.has('NAME'). */
public enum Permission {
    // Team
    TEAM_VIEW("Team", "View staff"),
    TEAM_INVITE("Team", "Invite staff"),
    TEAM_EDIT("Team", "Edit staff details"),
    TEAM_DEACTIVATE("Team", "Deactivate and reactivate staff"),
    TEAM_ASSIGN_ROLES("Team", "Assign roles"),
    ROLES_CREATE("Team", "Create custom roles"),
    PERMISSIONS_MANAGE("Team", "Edit role permissions"),
    // Dashboard
    DASHBOARD_VIEW("Dashboard", "View the dashboard"),
    DASHBOARD_FINANCIAL("Dashboard", "See profit, expenses and money figures"),
    DASHBOARD_OPERATIONAL("Dashboard", "See operational figures"),
    // Products and menu
    PRODUCTS_VIEW("Products and menu", "View products"),
    PRODUCTS_CREATE("Products and menu", "Add products"),
    PRODUCTS_EDIT("Products and menu", "Edit products"),
    PRODUCTS_ARCHIVE("Products and menu", "Archive and restore products"),
    CATEGORIES_MANAGE("Products and menu", "Manage categories"),
    VARIANTS_MANAGE("Products and menu", "Manage sizes and options"),
    MODIFIERS_MANAGE("Products and menu", "Manage add-ons and modifiers"),
    // Inventory
    STOCK_VIEW("Inventory", "View stock"),
    STOCK_RESTOCK("Inventory", "Add stock"),
    STOCK_ADJUST("Inventory", "Adjust or correct stock"),
    STOCK_MOVEMENTS_VIEW("Inventory", "View stock history"),
    INGREDIENTS_MANAGE("Inventory", "Manage ingredients"),
    RECIPES_MANAGE("Inventory", "Manage recipes"),
    // Sales and POS
    SALES_VIEW("Sales and POS", "View all sales"),
    SALES_CREATE("Sales and POS", "Record sales"),
    SALES_DISCOUNT("Sales and POS", "Give discounts"),
    SALES_REFUND("Sales and POS", "Refund sales"),
    SALES_HISTORY_VIEW("Sales and POS", "View own sales history"),
    RECEIPTS_GENERATE("Sales and POS", "Print receipts"),
    // Customers
    CUSTOMERS_VIEW("Customers", "View customers"),
    CUSTOMERS_CREATE("Customers", "Add customers"),
    CUSTOMERS_EDIT("Customers", "Edit customers"),
    CUSTOMER_BALANCES_VIEW("Customers", "View what customers owe"),
    REPAYMENTS_RECORD("Customers", "Record customer payments"),
    // Orders
    ORDERS_VIEW("Orders", "View orders"),
    ORDERS_CREATE("Orders", "Create orders"),
    ORDERS_EDIT("Orders", "Edit orders"),
    ORDERS_ACCEPT("Orders", "Accept orders"),
    ORDERS_CANCEL("Orders", "Cancel orders"),
    ORDERS_STATUS_UPDATE("Orders", "Update order status"),
    DELIVERY_MANAGE("Orders", "Manage delivery"),
    // Kitchen
    KITCHEN_VIEW("Kitchen", "View kitchen orders"),
    KITCHEN_STATUS_UPDATE("Kitchen", "Update preparation status"),
    KITCHEN_MARK_READY("Kitchen", "Mark orders ready"),
    // Finance
    FINANCE_OVERVIEW("Finance", "View the financial overview"),
    EXPENSES_VIEW("Finance", "View expenses"),
    EXPENSES_CREATE("Finance", "Add expenses"),
    EXPENSES_EDIT("Finance", "Edit and delete expenses"),
    PAYMENTS_VIEW("Finance", "View payment records"),
    CASH_ACCOUNTS_MANAGE("Finance", "Manage cash and bank records"),
    FINANCE_REPORTS_VIEW("Finance", "View financial reports"),
    FINANCE_EXPORT("Finance", "Export financial records"),
    // Storefront
    STORE_VIEW("Online store", "View store settings"),
    STORE_EDIT("Online store", "Edit store settings"),
    STORE_PUBLISH("Online store", "Publish the store"),
    STORE_DELIVERY_MANAGE("Online store", "Manage delivery settings"),
    STORE_ORDERS_MANAGE("Online store", "Manage online orders"),
    STORE_PAYMENTS_MANAGE("Online store", "Manage the payment connection"),
    // Purchasing
    SUPPLIERS_VIEW("Purchasing", "View suppliers"),
    SUPPLIERS_MANAGE("Purchasing", "Add and edit suppliers"),
    PURCHASES_VIEW("Purchasing", "View purchases"),
    PURCHASES_CREATE("Purchasing", "Receive stock from a supplier"),
    SUPPLIER_PAYMENTS_RECORD("Purchasing", "Record payments to suppliers"),
    // Reports
    REPORTS_SALES("Reports", "View sales reports"),
    REPORTS_INVENTORY("Reports", "View inventory reports"),
    REPORTS_CUSTOMERS("Reports", "View customer reports"),
    REPORTS_FINANCIAL("Reports", "View financial reports"),
    REPORTS_EXPORT("Reports", "Export reports"),
    // Business settings
    SETTINGS_VIEW("Business settings", "View business settings"),
    SETTINGS_EDIT("Business settings", "Edit business settings"),
    LOCATIONS_MANAGE("Business settings", "Manage locations"),
    INTEGRATIONS_MANAGE("Business settings", "Manage integrations"),
    // Subscription
    SUBSCRIPTION_VIEW("Subscription", "View the subscription"),
    SUBSCRIPTION_MANAGE("Subscription", "Change or cancel the subscription"),
    INVOICES_VIEW("Subscription", "View invoices"),
    BILLING_MANAGE("Subscription", "Manage billing details");

    private final String module;
    private final String label;

    Permission(String module, String label) { this.module = module; this.label = label; }

    public String module() { return module; }
    public String label() { return label; }
}
