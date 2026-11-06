package com.amsati.shopscale.business;

import com.amsati.shopscale.auth.AuthResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/businesses")
@RequiredArgsConstructor
public class BusinessController {
    private final BusinessService service;

    /** Onboarding. Returns fresh tokens that carry the new business context. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse create(@Valid @RequestBody BusinessService.CreateRequest r) { return service.create(r); }

    @GetMapping("/slug-available")
    public BusinessService.SlugCheck slug(@RequestParam String slug) { return service.checkSlug(slug); }

    @GetMapping("/current")
    public BusinessDto current() { return service.current(); }

    @PutMapping("/current")
    @PreAuthorize("@access.has('SETTINGS_EDIT')")
    public BusinessDto update(@Valid @RequestBody BusinessService.UpdateRequest r) { return service.update(r); }
}
