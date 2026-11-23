package com.amsati.shopscale.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class ProductImportController {
    private final ProductImportService importer;

    @PostMapping("/api/products/import")
    @PreAuthorize("@access.has('PRODUCTS_CREATE')")
    public ProductImportService.Result importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return importer.importCsv(file.getBytes());
    }

    @GetMapping("/api/products/import-template.csv")
    @PreAuthorize("@access.has('PRODUCTS_CREATE')")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shopscale-product-import-template.csv\"")
            .body(ProductImportService.TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }
}
