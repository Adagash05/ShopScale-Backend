package com.amsati.shopscale.partners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amsati.shopscale.auth.User;
import com.amsati.shopscale.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PlatformAdminBootstrapTest {
    private static final String GOOD = "Str0ng-Passw0rd!";
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder(4);

    private PlatformAdminBootstrap boot(String listed, String email, String pw) {
        return new PlatformAdminBootstrap(users, enc, new PartnerAccess(listed, users), email, pw, null);
    }

    @Test void createsOneVerifiedHashedAccountWhenEverythingIsSet() {
        assertTrue(boot("boss@example.com", "Boss@Example.com", GOOD).provision());
        ArgumentCaptor<User> c = ArgumentCaptor.forClass(User.class);
        verify(users).save(c.capture());
        assertEquals("boss@example.com", c.getValue().getEmail());
        assertTrue(c.getValue().isEmailVerified());
        assertNotEquals(GOOD, c.getValue().getPasswordHash());
        assertTrue(enc.matches(GOOD, c.getValue().getPasswordHash()));
    }

    @Test void doesNothingWithoutVariables() {
        assertFalse(boot("boss@example.com", "", "").provision());
        verify(users, never()).save(any());
    }

    @Test void refusesWhenEmailIsNotInTheAdminAllowList() {
        assertFalse(boot("other@example.com", "boss@example.com", GOOD).provision());
        verify(users, never()).save(any());
    }

    @Test void refusesWeakPasswords() {
        for (String pw : new String[] {"short1A!", "alllowercase123!", "ALLUPPERCASE123!", "NoDigitsHere!!!!", "NoSymbolsHere1234"}) {
            assertFalse(boot("boss@example.com", "boss@example.com", pw).provision(), pw);
        }
        verify(users, never()).save(any());
    }

    @Test void neverRunsAgainOnceAnAdministratorAccountExists() {
        when(users.existsByEmail("boss@example.com")).thenReturn(true);
        assertFalse(boot("boss@example.com", "boss@example.com", GOOD).provision());
        assertFalse(boot("boss@example.com,second@example.com", "second@example.com", GOOD).provision());   // cannot add a second "initial" admin
        verify(users, never()).save(any());
    }
}
