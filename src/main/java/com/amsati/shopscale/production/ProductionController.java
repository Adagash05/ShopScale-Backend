package com.amsati.shopscale.production;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/production")
@RequiredArgsConstructor
public class ProductionController {
    private final ProductionService service;

    @GetMapping("/runs")
    @PreAuthorize("@access.has('PRODUCTION_MANAGE')")
    public List<ProductionService.Dto> list(@RequestParam(defaultValue = "last7") String range,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(range, from, to);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('PRODUCTION_MANAGE')")
    public ProductionService.Dto record(@Valid @RequestBody ProductionService.Request r) { return service.record(r); }
}
