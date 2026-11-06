package com.amsati.shopscale.security;

import static com.amsati.shopscale.security.Permission.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultRolesTest {

    @Test
    void ownerHasEverything() {
        assertEquals(EnumSet.allOf(Permission.class), DefaultRoles.permissions(Role.OWNER));
    }

    @Test
    void administratorCannotTouchBillingOrPaymentCredentials() {
        Set<Permission> p = DefaultRoles.permissions(Role.ADMINISTRATOR);
        assertFalse(p.contains(SUBSCRIPTION_MANAGE));
        assertFalse(p.contains(BILLING_MANAGE));
        assertFalse(p.contains(STORE_PAYMENTS_MANAGE));
        assertTrue(p.contains(TEAM_INVITE));
        assertTrue(p.contains(PRODUCTS_CREATE));
    }

    @Test
    void cashierSellsButSeesNoMoneyReportsOrStaff() {
        Set<Permission> p = DefaultRoles.permissions(Role.SALES_STAFF);
        assertTrue(p.contains(SALES_CREATE));
        assertTrue(p.contains(SALES_HISTORY_VIEW));
        assertFalse(p.contains(SALES_VIEW));          // own sales only
        assertFalse(p.contains(SALES_REFUND));
        assertFalse(p.contains(SALES_DISCOUNT));
        assertFalse(p.contains(DASHBOARD_FINANCIAL));
        assertFalse(p.contains(EXPENSES_VIEW));
        assertFalse(p.contains(TEAM_VIEW));
        assertFalse(p.contains(SUBSCRIPTION_VIEW));
    }

    @Test
    void kitchenStaffHaveOnlyKitchenAccess() {
        assertEquals(EnumSet.of(KITCHEN_VIEW, KITCHEN_STATUS_UPDATE, KITCHEN_MARK_READY), DefaultRoles.permissions(Role.KITCHEN_STAFF));
    }

    @Test
    void inventoryStaffManageStockButNotMoneyOrTeam() {
        Set<Permission> p = DefaultRoles.permissions(Role.INVENTORY_STAFF);
        assertTrue(p.containsAll(Set.of(STOCK_RESTOCK, STOCK_ADJUST, STOCK_MOVEMENTS_VIEW, PRODUCTS_CREATE)));
        assertFalse(p.contains(SALES_REFUND));
        assertFalse(p.contains(EXPENSES_VIEW));
        assertFalse(p.contains(TEAM_INVITE));
        assertFalse(p.contains(SUBSCRIPTION_VIEW));
    }

    @Test
    void accountantSeesMoneyButCannotManageOwnershipBillingOrTeam() {
        Set<Permission> p = DefaultRoles.permissions(Role.ACCOUNTANT);
        assertTrue(p.containsAll(Set.of(EXPENSES_VIEW, EXPENSES_CREATE, FINANCE_REPORTS_VIEW, FINANCE_EXPORT, CUSTOMER_BALANCES_VIEW)));
        assertFalse(p.contains(SUBSCRIPTION_MANAGE));
        assertFalse(p.contains(TEAM_INVITE));
        assertFalse(p.contains(ROLES_CREATE));
        assertFalse(p.contains(SETTINGS_EDIT));
    }

    @Test
    void managerDoesNotSeeProfitByDefault() {
        Set<Permission> p = DefaultRoles.permissions(Role.MANAGER);
        assertTrue(p.contains(DASHBOARD_VIEW));
        assertFalse(p.contains(DASHBOARD_FINANCIAL));
        assertFalse(p.contains(EXPENSES_VIEW));
        assertFalse(p.contains(TEAM_INVITE));
    }

    @Test
    void noBuiltInRoleOtherThanOwnerCanManagePlatformOrOwnership() {
        for (Role r : Role.values()) {
            if (r == Role.OWNER || r == Role.CUSTOM) continue;
            assertFalse(DefaultRoles.permissions(r).equals(EnumSet.allOf(Permission.class)), r + " must not equal owner");
        }
    }

    @Test
    void customRoleHasNoBuiltInPermissions() {
        assertTrue(DefaultRoles.permissions(Role.CUSTOM).isEmpty());
    }

    @Test
    void youCanOnlyGrantWhatYouHold() {
        Set<Permission> manager = DefaultRoles.permissions(Role.MANAGER);
        assertTrue(DefaultRoles.canGrant(manager, DefaultRoles.permissions(Role.SALES_STAFF)));
        assertFalse(DefaultRoles.canGrant(manager, DefaultRoles.permissions(Role.ACCOUNTANT)));      // has money permissions manager lacks
        assertFalse(DefaultRoles.canGrant(manager, DefaultRoles.permissions(Role.OWNER)));
        assertFalse(DefaultRoles.canGrant(manager, Set.of(TEAM_INVITE)));
        assertTrue(DefaultRoles.canGrant(DefaultRoles.permissions(Role.OWNER), DefaultRoles.permissions(Role.ADMINISTRATOR)));
    }

    @Test
    void catalogueEntriesAreComplete() {
        for (Permission p : Permission.values()) {
            assertFalse(p.module().isBlank());
            assertFalse(p.label().isBlank());
        }
    }
}
