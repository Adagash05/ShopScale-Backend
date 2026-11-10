package com.amsati.shopscale.finance;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {
    private final FinanceService service;

    @GetMapping("/summary")
    @PreAuthorize("@access.has('FINANCE_OVERVIEW')")
    public FinanceService.Summary summary(@RequestParam(defaultValue = "thismonth") String range,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(range, from, to);
    }

    @GetMapping("/debtors")
    @PreAuthorize("@access.has('FINANCE_OVERVIEW')")
    public List<FinanceService.Debtor> debtors() { return service.debtors(); }

    @GetMapping("/payables")
    @PreAuthorize("@access.has('FINANCE_OVERVIEW')")
    public List<FinanceService.Payable> payables() { return service.payables(); }

    @GetMapping("/accounts")
    @PreAuthorize("@access.has('FINANCE_OVERVIEW') or @access.has('CASH_ACCOUNTS_MANAGE')")
    public List<FinanceService.AccountDto> accounts() { return service.listAccounts(); }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CASH_ACCOUNTS_MANAGE')")
    public FinanceService.AccountDto create(@Valid @RequestBody FinanceService.AccountRequest r) { return service.createAccount(r); }

    public record RenameRequest(String name, boolean active) {}

    @PutMapping("/accounts/{id}")
    @PreAuthorize("@access.has('CASH_ACCOUNTS_MANAGE')")
    public FinanceService.AccountDto rename(@PathVariable UUID id, @RequestBody RenameRequest r) { return service.renameAccount(id, r.name(), r.active()); }

    @GetMapping("/accounts/{id}/movements")
    @PreAuthorize("@access.has('FINANCE_OVERVIEW') or @access.has('CASH_ACCOUNTS_MANAGE')")
    public Page<FinanceService.MovementDto> movements(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.accountMovements(id, page, size);
    }

    @PostMapping("/manual")
    @PreAuthorize("@access.has('CASH_ACCOUNTS_MANAGE')")
    public FinanceService.AccountDto manual(@Valid @RequestBody FinanceService.ManualRequest r) { return service.manual(r); }

    @PostMapping("/transfer")
    @PreAuthorize("@access.has('CASH_ACCOUNTS_MANAGE')")
    public List<FinanceService.AccountDto> transfer(@Valid @RequestBody FinanceService.TransferRequest r) { return service.transfer(r); }

    // ---------- cashier shifts ----------
    private final ShiftService shiftService;

    @GetMapping("/shifts/current")
    @PreAuthorize("@access.has('SHIFTS_MANAGE')")
    public java.util.Optional<ShiftService.ShiftDto> currentShift() { return shiftService.current(); }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('SHIFTS_MANAGE')")
    public ShiftService.ShiftDto openShift(@Valid @RequestBody(required = false) ShiftService.OpenRequest r) { return shiftService.open(r); }

    @PostMapping("/shifts/{id}/close")
    @PreAuthorize("@access.has('SHIFTS_MANAGE') or @access.has('SHIFTS_RECONCILE')")
    public ShiftService.ShiftDto closeShift(@PathVariable UUID id, @Valid @RequestBody ShiftService.CloseRequest r) { return shiftService.close(id, r); }

    @PostMapping("/shifts/{id}/review")
    @PreAuthorize("@access.has('SHIFTS_RECONCILE')")
    public ShiftService.ShiftDto reviewShift(@PathVariable UUID id, @RequestBody(required = false) ShiftService.ReviewRequest r) { return shiftService.review(id, r == null ? null : r.note()); }

    @GetMapping("/shifts")
    @PreAuthorize("@access.has('SHIFTS_MANAGE') or @access.has('SHIFTS_RECONCILE')")
    public Page<ShiftService.ShiftDto> shifts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) { return shiftService.list(page, size); }
}
