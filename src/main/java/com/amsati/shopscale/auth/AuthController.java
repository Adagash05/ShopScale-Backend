package com.amsati.shopscale.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService service;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody AuthService.RegisterRequest r) { return service.register(r); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthService.LoginRequest r) { return service.login(r); }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody AuthService.RefreshRequest r) { return service.refresh(r.refreshToken()); }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody AuthService.RefreshRequest r) { service.logout(r.refreshToken()); }

    @GetMapping("/businesses")
    public java.util.List<AuthService.BusinessOption> businesses() { return service.myBusinesses(); }

    @PostMapping("/switch")
    public AuthResponse switchBusiness(@Valid @RequestBody AuthService.SwitchRequest r) { return service.switchBusiness(r.businessId()); }

    /** Current user, business, role and permissions for the signed-in session. */
    @GetMapping("/me")
    public AuthResponse me() { return service.session(); }
}
