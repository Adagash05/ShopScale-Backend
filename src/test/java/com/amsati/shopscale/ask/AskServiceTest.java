package com.amsati.shopscale.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.customer.CustomerRepository;
import com.amsati.shopscale.dashboard.DashboardOverviewService;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.AuthPrincipal;
import com.amsati.shopscale.security.Permission;
import com.amsati.shopscale.subscription.SubscriptionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The plan check and the permission check come before any business data is read. */
class AskServiceTest {
    DashboardOverviewService overviews = mock(DashboardOverviewService.class);
    CustomerRepository customers = mock(CustomerRepository.class);
    SubscriptionService subs = mock(SubscriptionService.class);
    AccessService access = mock(AccessService.class);
    com.amsati.shopscale.invoice.InvoiceReporting invoiceReporting = mock(com.amsati.shopscale.invoice.InvoiceReporting.class);
    AskService service = new AskService(overviews, customers, subs, access, invoiceReporting);
    UUID business = UUID.randomUUID();

    @BeforeEach void signIn() {
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), "owner@example.com", business, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }
    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    @Test void aPlanWithoutTheFeatureIsRefusedBeforeAnyDataIsRead() {
        doThrow(ApiException.paymentRequired("Ask ShopScale is not included in your Free plan. Upgrade to use it."))
            .when(subs).requireFeature(eq(business), eq("ASK_SHOPSCALE"), any());
        assertThrows(ApiException.class, () -> service.ask("SALES", "today", null, null));
        assertThrows(ApiException.class, () -> service.questions());
        verify(overviews, never()).overview(any(), any(), any());
        verify(customers, never()).owing(any());
    }
    @Test void moneyQuestionsNeedFinancialAccess() {
        when(access.current()).thenReturn(Set.of(Permission.DASHBOARD_VIEW));
        var a = service.ask("MONEY_RECEIVED", "today", null, null);
        assertFalse(a.available());
        verify(overviews, never()).overview(any(), any(), any());
    }
    @Test void anUnknownQuestionIsRejected() {
        when(access.current()).thenReturn(Set.of(Permission.DASHBOARD_VIEW, Permission.DASHBOARD_FINANCIAL));
        assertThrows(ApiException.class, () -> service.ask("DROP_TABLE", "today", null, null));
    }
    @Test void questionsMarkFinancialOnesAsNotAllowedForStaff() {
        when(access.current()).thenReturn(Set.of(Permission.DASHBOARD_VIEW));
        var qs = service.questions();
        assertEquals(8, qs.size());
        assertFalse(qs.stream().filter(q -> q.key().equals("TOP_EXPENSES")).findFirst().orElseThrow().allowed());
    }
}
