package com.amsati.shopscale.transfer;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService service;

    @GetMapping
    @PreAuthorize("@access.has('STOCK_VIEW')")
    public Page<TransferService.TransferDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has('STOCK_VIEW')")
    public TransferService.TransferDto get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('STOCK_TRANSFER')")
    public TransferService.TransferDto create(@Valid @RequestBody TransferService.TransferRequest r) { return service.create(r); }

    @PostMapping("/{id}/receive")
    @PreAuthorize("@access.has('STOCK_TRANSFER')")
    public TransferService.TransferDto receive(@PathVariable UUID id) { return service.receive(id); }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@access.has('STOCK_TRANSFER')")
    public TransferService.TransferDto cancel(@PathVariable UUID id) { return service.cancel(id); }
}
