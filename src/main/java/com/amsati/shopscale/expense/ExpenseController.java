package com.amsati.shopscale.expense;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {
    private final ExpenseService service;

    @GetMapping
    @PreAuthorize("@access.has('EXPENSES_VIEW')")
    public ExpenseService.ExpensePage list(@RequestParam(defaultValue = "thismonth") String range,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "30") int size) {
        return service.list(range, from, to, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('EXPENSES_CREATE')")
    public ExpenseService.ExpenseDto create(@Valid @RequestBody ExpenseService.ExpenseRequest r) { return service.create(r); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has('EXPENSES_EDIT')")
    public void delete(@PathVariable UUID id) { service.delete(id); }

    public record LimitRequest(java.math.BigDecimal limit) {}

    @PostMapping("/{id}/approve")
    @PreAuthorize("@access.has('EXPENSES_APPROVE')")
    public ExpenseService.ExpenseDto approve(@PathVariable UUID id, @RequestBody(required = false) ExpenseService.DecisionRequest r) {
        return service.decide(id, true, r == null ? null : r.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@access.has('EXPENSES_APPROVE')")
    public ExpenseService.ExpenseDto reject(@PathVariable UUID id, @RequestBody(required = false) ExpenseService.DecisionRequest r) {
        return service.decide(id, false, r == null ? null : r.note());
    }

    @GetMapping("/approval-limit")
    @PreAuthorize("@access.has('EXPENSES_VIEW')")
    public LimitRequest limit() { return new LimitRequest(service.approvalLimit()); }

    @PutMapping("/approval-limit")
    @PreAuthorize("@access.has('SETTINGS_EDIT')")
    public LimitRequest setLimit(@RequestBody LimitRequest r) { return new LimitRequest(service.setApprovalLimit(r.limit())); }
}
