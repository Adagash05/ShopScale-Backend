package com.amsati.shopscale.invoice;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The link a customer opens from a WhatsApp message. The 256-bit random token is the only credential. */
@RestController
@RequestMapping("/api/public/invoices")
@RequiredArgsConstructor
public class PublicInvoiceController {
    private final InvoiceService service;

    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String token) {
        return InvoiceController.pdfResponse(service.publicPdf(token), "invoice.pdf", true);
    }
}
