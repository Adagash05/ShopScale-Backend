package com.amsati.shopscale.customer;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService service;

    @GetMapping
    @PreAuthorize("@access.has('CUSTOMERS_VIEW')")
    public Page<CustomerService.CustomerDto> list(@RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "false") boolean owingOnly,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "30") int size) {
        return service.list(q, owingOnly, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CUSTOMERS_CREATE')")
    public CustomerService.CustomerDto create(@Valid @RequestBody CustomerService.CustomerRequest r) { return service.create(r); }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has('CUSTOMERS_VIEW')")
    public CustomerService.CustomerDetail detail(@PathVariable UUID id) { return service.detail(id); }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has('CUSTOMERS_EDIT')")
    public CustomerService.CustomerDto update(@PathVariable UUID id, @Valid @RequestBody CustomerService.CustomerRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("@access.has('REPAYMENTS_RECORD')")
    public CustomerService.CustomerDetail pay(@PathVariable UUID id, @Valid @RequestBody CustomerService.PaymentRequest r) {
        return service.recordPayment(id, r);
    }
}
