package com.amsati.shopscale.store;

import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.security.Tenant;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** One image per product, stored in the database. The type is decided from the file's own bytes, not its name or declared type. */
@RestController
@RequiredArgsConstructor
public class ProductImageController {
    private static final int MAX_BYTES = 600 * 1024;
    private final ProductImageRepository images;
    private final ProductRepository products;
    @Value("${app.public-api-url:}") private String publicApiUrl;

    @PostMapping("/api/products/{id}/image")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    @Transactional
    public Map<String, String> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        UUID bid = Tenant.businessId();
        Product p = products.findById(id).filter(x -> x.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Product not found"));
        byte[] bytes = file.getBytes();
        if (bytes.length == 0) throw ApiException.badRequest("Choose an image");
        if (bytes.length > MAX_BYTES) throw ApiException.badRequest("The image is too large. Use one under 600 KB.");
        String type = sniff(bytes);
        if (type == null) throw ApiException.badRequest("Use a JPEG, PNG or WebP image");
        ProductImage img = new ProductImage();
        img.setBusinessId(bid);
        img.setProductId(id);
        img.setContentType(type);
        img.setSizeBytes(bytes.length);
        img.setData(bytes);
        img = images.save(img);
        String url = publicApiUrl.replaceAll("/+$", "") + "/api/public/images/" + img.getId();
        p.setImageUrl(url);
        p.setUpdatedAt(Instant.now());
        return Map.of("imageUrl", url);
    }

    @DeleteMapping("/api/products/{id}/image")
    @PreAuthorize("@access.has('PRODUCTS_EDIT')")
    @Transactional
    public void remove(@PathVariable UUID id) {
        UUID bid = Tenant.businessId();
        Product p = products.findById(id).filter(x -> x.getBusinessId().equals(bid)).orElseThrow(() -> ApiException.notFound("Product not found"));
        p.setImageUrl(null);
        p.setUpdatedAt(Instant.now());
    }

    @GetMapping("/api/public/images/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> serve(@PathVariable UUID id) {
        ProductImage img = images.findById(id).orElseThrow(() -> ApiException.notFound("Image not found"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.getContentType()))
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic().immutable()).body(img.getData());
    }

    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
