package com.amsati.shopscale.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amsati.shopscale.business.BusinessRepository;
import com.amsati.shopscale.business.MembershipRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.AccessService;
import com.amsati.shopscale.security.JwtService;
import com.amsati.shopscale.subscription.SubscriptionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class RegistrationTest {
    UserRepository users = mock(UserRepository.class);
    AccountService account = mock(AccountService.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    AuthService svc = new AuthService(users, mock(RefreshTokenRepository.class), mock(MembershipRepository.class), mock(BusinessRepository.class),
        mock(JwtService.class), encoder, mock(AccessService.class), account, mock(SubscriptionService.class));
    static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    private AuthService.RegisterRequest req(String email, String pw, String name) { return new AuthService.RegisterRequest(email, pw, name, null); }

    @Test void aDuplicateEmailIsRefusedBeforeAnythingIsCreatedOrSent() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);
        ApiException e = assertThrows(ApiException.class, () -> svc.register(req(" Ada@Example.com ", "longenough1", "Ada")));
        assertTrue(e.getMessage().contains("already exists"));
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(account);
    }

    @Test void twoSimultaneousSignUpsLoseCleanlyOnTheUniqueIndex() {
        when(encoder.encode(any())).thenReturn("hash");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));
        ApiException e = assertThrows(ApiException.class, () -> svc.register(req("ada@example.com", "longenough1", "Ada")));
        assertTrue(e.getMessage().contains("already exists"));
        verifyNoInteractions(account);   // no confirmation email for an account that was not created
    }

    @Test void invalidDataIsRejectedByValidation() {
        assertEquals(0, V.validate(req("ada@example.com", "longenough1", "Ada Obi")).size());
        assertFalse(V.validate(req("not-an-email", "longenough1", "Ada")).isEmpty());
        assertFalse(V.validate(req("ada@example.com", "short", "Ada")).isEmpty());
        assertFalse(V.validate(req("ada@example.com", "x".repeat(73), "Ada")).isEmpty());
        assertFalse(V.validate(req("ada@example.com", "longenough1", " ")).isEmpty());
        assertFalse(V.validate(req("", "longenough1", "Ada")).isEmpty());
    }
}
