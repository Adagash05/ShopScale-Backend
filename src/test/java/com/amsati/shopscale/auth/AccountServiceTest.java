package com.amsati.shopscale.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AuthPrincipal;
import com.amsati.shopscale.security.TokenHash;
import com.amsati.shopscale.team.EmailService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AccountServiceTest {
    UserRepository users = mock(UserRepository.class);
    AuthTokenRepository tokens = mock(AuthTokenRepository.class);
    RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    EmailService email = mock(EmailService.class);
    AccountService svc;
    User user;

    @BeforeEach void setUp() {
        svc = new AccountService(users, tokens, refresh, encoder, email);
        ReflectionTestUtils.setField(svc, "frontendUrl", "https://app.shopscale.com.ng/");
        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("ada@example.com");
        user.setFullName("Ada Obi");
        when(email.isConfigured()).thenReturn(true);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.existsByUserIdAndKindAndUsedAtIsNullAndCreatedAtAfter(any(), any(), any())).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(user.getId(), user.getEmail(), null, null), null, java.util.List.of()));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void verificationCreatesAHashedTokenLocksTheUserAndQueuesTheEmailAfterCommit() {
        svc.sendVerification(user);
        var order = inOrder(users, tokens, email);
        order.verify(users).lockById(user.getId());
        order.verify(tokens).invalidateOpen(eq(user.getId()), eq("VERIFY"), any());
        ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
        order.verify(tokens).save(saved.capture());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        order.verify(email).sendAfterCommit(eq("ada@example.com"), anyString(), text.capture(), anyString());
        verify(email, never()).send(any(), any(), any());   // never the blocking path

        String link = text.getValue().lines().filter(l -> l.contains("/verify-email?token=")).findFirst().orElseThrow();
        assertTrue(link.startsWith("https://app.shopscale.com.ng/verify-email?token="), "no double slash from the trailing slash in FRONTEND_URL");
        String raw = link.substring(link.indexOf("token=") + 6);
        assertEquals(TokenHash.sha256(raw), saved.getValue().getTokenHash());
        assertNotEquals(raw, saved.getValue().getTokenHash());
        assertTrue(saved.getValue().getExpiresAt().isAfter(Instant.now().plusSeconds(2 * 24 * 3600)));
    }

    @Test void aSecondRequestInsideTheCooldownDoesNotIssueAnotherTokenOrEmail() {
        when(tokens.existsByUserIdAndKindAndUsedAtIsNullAndCreatedAtAfter(eq(user.getId()), eq("VERIFY"), any())).thenReturn(true);
        svc.sendVerification(user);
        verify(tokens, never()).save(any());
        verify(email, never()).sendAfterCommit(any(), any(), any(), any());
    }

    @Test void resendIsRefusedClearlyWhenEmailIsNotConfigured() {
        when(email.isConfigured()).thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> svc.resendVerification());
        assertTrue(e.getMessage().contains("not set up"));
    }

    @Test void resendDoesNothingForAnAlreadyVerifiedAccount() {
        user.setEmailVerified(true);
        svc.resendVerification();
        verify(tokens, never()).save(any());
    }

    private AuthToken tokenFor(String raw, Instant expires, Instant used) {
        AuthToken t = new AuthToken();
        t.setId(UUID.randomUUID());
        t.setUserId(user.getId());
        t.setKind("VERIFY");
        t.setTokenHash(TokenHash.sha256(raw));
        t.setExpiresAt(expires);
        t.setUsedAt(used);
        when(tokens.findByTokenHashAndKind(TokenHash.sha256(raw), "VERIFY")).thenReturn(Optional.of(t));
        return t;
    }

    @Test void aGoodLinkVerifiesTheAccountOnce() {
        AuthToken t = tokenFor("good", Instant.now().plusSeconds(600), null);
        when(tokens.consume(eq(t.getId()), any())).thenReturn(1);
        svc.verify(new AccountService.TokenRequest("good"));
        assertTrue(user.isEmailVerified());
    }

    @Test void anExpiredLinkIsRejected() {
        tokenFor("old", Instant.now().minusSeconds(5), null);
        assertThrows(ApiException.class, () -> svc.verify(new AccountService.TokenRequest("old")));
        assertFalse(user.isEmailVerified());
    }

    @Test void aSpentLinkCannotBeReused() {
        tokenFor("used", Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        assertThrows(ApiException.class, () -> svc.verify(new AccountService.TokenRequest("used")));
        assertFalse(user.isEmailVerified());
    }

    @Test void ofTwoSimultaneousUsesOfOneLinkTheLoserIsRejected() {
        AuthToken t = tokenFor("race", Instant.now().plusSeconds(600), null);
        when(tokens.consume(eq(t.getId()), any())).thenReturn(0);   // the other request's UPDATE got there first
        assertThrows(ApiException.class, () -> svc.verify(new AccountService.TokenRequest("race")));
        assertFalse(user.isEmailVerified());
    }

    @Test void anUnknownLinkIsRejectedWithoutDetail() {
        when(tokens.findByTokenHashAndKind(any(), any())).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class, () -> svc.verify(new AccountService.TokenRequest("nope")));
        assertTrue(e.getMessage().contains("not valid"));
    }

    @Test void passwordResetUsesTheSameAfterCommitEmailAndNeverRevealsWhetherTheAccountExists() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        svc.forgotPassword(new AccountService.ForgotRequest(" Ada@Example.com "));
        verify(email).sendAfterCommit(eq("ada@example.com"), contains("Reset"), contains("/reset-password?token="), anyString());
        assertDoesNotThrow(() -> svc.forgotPassword(new AccountService.ForgotRequest("ghost@example.com")));
        verify(email, times(1)).sendAfterCommit(any(), any(), any(), any());
    }
}
