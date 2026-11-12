package com.amsati.shopscale.auth;

import com.amsati.shopscale.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService service;
    private final AccountService account;
    private final RefreshCookies cookies;

    @Value("${app.cors.allowed-origins:}") private String allowedOrigins;

    /**
     * Cookie-authenticated calls (refresh, logout) must come from a ShopScale page: the custom header cannot be sent
     * by another site without a CORS preflight, and a browser-supplied Origin must be one of ours.
     */
    private void requireOurClient(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        List<String> ok = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (origin != null && !ok.contains(origin)) throw ApiException.forbidden("This request is not allowed from here");
        if (!"web".equals(req.getHeader("X-ShopScale-Client"))) throw ApiException.forbidden("This request is not allowed from here");
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@Valid @RequestBody AccountService.ForgotRequest r) { account.forgotPassword(r); }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody AccountService.ResetRequest r) { account.resetPassword(r); }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody AccountService.TokenRequest r) { account.verify(r); }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend() { account.resendVerification(); }

    @GetMapping("/verification-status")
    public AccountService.VerificationStatus verification() { return account.status(); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody AuthService.RegisterRequest r) { return service.register(r); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthService.LoginRequest r) { return service.login(r); }

    /** The refresh token comes from the HttpOnly cookie (browsers); a JSON body is still accepted for non-browser clients. */
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody(required = false) AuthService.RefreshRequest r, HttpServletRequest req) {
        String raw = cookies.read(req);
        if (raw != null) requireOurClient(req);
        else if (r != null) raw = r.refreshToken();
        if (raw == null || raw.isBlank()) throw ApiException.unauthorized("Sign in to continue");
        return service.refresh(raw);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) AuthService.RefreshRequest r, HttpServletRequest req, HttpServletResponse res) {
        String raw = cookies.read(req);
        if (raw != null) requireOurClient(req);
        else if (r != null) raw = r.refreshToken();
        service.logout(raw);
        res.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    }

    /** Devices signed in to this account. */
    @GetMapping("/sessions")
    public List<AuthService.SessionDto> sessions(HttpServletRequest req) { return service.sessions(cookies.read(req)); }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) { service.revokeSession(id); }

    /** Sign out of every device, including this one. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(HttpServletResponse res) {
        service.revokeAllSessions();
        res.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    }

    @GetMapping("/businesses")
    public java.util.List<AuthService.BusinessOption> businesses() { return service.myBusinesses(); }

    @PostMapping("/switch")
    public AuthResponse switchBusiness(@Valid @RequestBody AuthService.SwitchRequest r) { return service.switchBusiness(r.businessId()); }

    /** Current user, business, role and permissions for the signed-in session. */
    @GetMapping("/me")
    public AuthResponse me() { return service.session(); }
}
