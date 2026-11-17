package com.amsati.shopscale.wastage;

import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wastage")
@RequiredArgsConstructor
public class WastageController {
    private final WastageService service;

    @GetMapping
    @PreAuthorize("@access.has('STOCK_WASTAGE')")
    public WastageService.ListDto list(@RequestParam(defaultValue = "last7") String range,
                                       @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(range, from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('STOCK_WASTAGE')")
    public WastageService.Dto record(@Valid @RequestBody WastageService.Request r) { return service.record(r); }
}
