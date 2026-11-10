package com.amsati.shopscale.sales;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sales-documents")
@RequiredArgsConstructor
public class SalesDocumentController {
    private final SalesDocumentService service;

    @GetMapping
    @PreAuthorize("@access.has('QUOTES_MANAGE')")
    public Page<SalesDocumentService.DocDto> list(@RequestParam String kind, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.list(kind, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has('QUOTES_MANAGE')")
    public SalesDocumentService.DocDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('QUOTES_MANAGE')")
    public SalesDocumentService.DocDto create(@Valid @RequestBody SalesDocumentService.DocRequest r) { return service.create(r); }

    @PostMapping("/{id}/accept")
    @PreAuthorize("@access.has('QUOTES_MANAGE')")
    public SalesDocumentService.DocDto accept(@PathVariable UUID id) { return service.accept(id); }

    @PostMapping("/{id}/convert")
    @PreAuthorize("@access.has('QUOTES_MANAGE') and @access.has('SALES_CREATE')")
    public SalesDocumentService.DocDto convert(@PathVariable UUID id, @Valid @RequestBody(required = false) SalesDocumentService.ConvertRequest r) { return service.convert(id, r); }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@access.has('QUOTES_MANAGE')")
    public SalesDocumentService.DocDto cancel(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean refundDeposit) { return service.cancel(id, refundDeposit); }
}
