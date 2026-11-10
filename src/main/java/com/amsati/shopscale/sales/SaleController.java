package com.amsati.shopscale.sales;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SaleController {
    private final SaleService service;
    private final SaleReturnService returns;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('SALES_CREATE')")
    public SaleService.SaleDto create(@Valid @RequestBody SaleService.SaleRequest r) { return service.create(r); }

    @GetMapping
    @PreAuthorize("@access.has('SALES_VIEW') or @access.has('SALES_HISTORY_VIEW')")
    public Page<SaleSummary> list(@RequestParam(defaultValue = "today") String range,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "30") int size) {
        return service.list(range, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has('SALES_VIEW') or @access.has('SALES_HISTORY_VIEW')")
    public SaleService.SaleDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/{id}/refund")
    @PreAuthorize("@access.has('SALES_REFUND')")
    public SaleService.SaleDto refund(@PathVariable UUID id) { return returns.refundAll(id, null); }

    @PostMapping("/{id}/refund-items")
    @PreAuthorize("@access.has('SALES_REFUND')")
    public SaleService.SaleDto refundItems(@PathVariable UUID id, @Valid @RequestBody SaleReturnService.RefundRequest r) { return returns.refundPartial(id, r); }

    @PostMapping("/{id}/void")
    @PreAuthorize("@access.has('SALES_VOID')")
    public SaleService.SaleDto voidSale(@PathVariable UUID id, @Valid @RequestBody SaleReturnService.VoidRequest r) { return returns.voidSale(id, r); }

    private final SalesReportService reports;
    private final com.amsati.shopscale.security.AccessService access;

    @GetMapping("/report")
    @PreAuthorize("@access.has('REPORTS_SALES')")
    public SalesReportService.Report report(@RequestParam String groupBy, @RequestParam(defaultValue = "thismonth") String range,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.report(groupBy, range, from, to, access.current().contains(com.amsati.shopscale.security.Permission.DASHBOARD_FINANCIAL));
    }
}
