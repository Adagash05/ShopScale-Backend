package com.amsati.shopscale.invoice;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {
    private final InvoiceService service;

    @GetMapping
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW')")
    public Page<InvoiceService.InvoiceSummary> list(@RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String q,
                                                    @RequestParam(required = false) UUID customerId,
                                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.list(status, q, customerId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_MANAGE')")
    public InvoiceService.InvoiceDto create(@Valid @RequestBody InvoiceService.InvoiceRequest r) { return service.create(r); }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW')")
    public InvoiceService.InvoiceDto get(@PathVariable UUID id) { return service.get(id); }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_MANAGE')")
    public InvoiceService.InvoiceDto update(@PathVariable UUID id, @Valid @RequestBody InvoiceService.InvoiceRequest r) { return service.update(id, r); }

    @PostMapping("/{id}/sent")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_MANAGE')")
    public InvoiceService.InvoiceDto sent(@PathVariable UUID id) { return service.markSent(id); }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_MANAGE')")
    public InvoiceService.InvoiceDto cancel(@PathVariable UUID id) { return service.cancel(id); }

    @PostMapping("/{id}/payments")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_PAYMENT_RECORD')")
    public InvoiceService.InvoiceDto pay(@PathVariable UUID id, @Valid @RequestBody InvoiceService.PaymentRequest r) { return service.recordPayment(id, r); }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return pdfResponse(service.pdf(id), service.filenameFor(id), false);
    }

    @GetMapping("/settings")
    @PreAuthorize("@access.has('CUSTOMER_INVOICES_VIEW')")
    public InvoiceService.SettingsDto settings() { return service.settings(); }

    @PutMapping("/settings")
    @PreAuthorize("@access.has('SETTINGS_EDIT')")
    public InvoiceService.SettingsDto saveSettings(@Valid @RequestBody InvoiceService.SettingsRequest r) { return service.saveSettings(r); }

    @PostMapping("/settings/logo")
    @PreAuthorize("@access.has('SETTINGS_EDIT')")
    public InvoiceService.SettingsDto logo(@RequestParam("file") MultipartFile file) throws IOException { return service.saveLogo(file.getBytes(), file.getContentType()); }

    @DeleteMapping("/settings/logo")
    @PreAuthorize("@access.has('SETTINGS_EDIT')")
    public InvoiceService.SettingsDto removeLogo() { return service.removeLogo(); }

    static ResponseEntity<byte[]> pdfResponse(byte[] data, String filename, boolean inline) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(filename).build().toString())
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(data);
    }
}
