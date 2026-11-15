package com.amsati.shopscale.store;

import com.amsati.shopscale.catalog.Product;
import com.amsati.shopscale.catalog.ProductRepository;
import com.amsati.shopscale.common.ApiException;
import com.amsati.shopscale.media.ImageProcessor;
import com.amsati.shopscale.media.MediaHttp;
import com.amsati.shopscale.media.MediaKeys.Kind;
import com.amsati.shopscale.media.MediaService;
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
    private final MediaService media;
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
        var info = ImageProcessor.inspect(bytes);
        if (info == null) throw ApiException.badRequest("Use a valid JPEG, PNG or WebP image");
        String type = info.contentType();
        dropCurrent(p, bid);
        ProductImage img = new ProductImage();
        img.setBusinessId(bid);
        img.setProductId(id);
        img.setContentType(type);
        img.setSizeBytes(bytes.length);
        boolean toR2 = media.r2Active();
        if (!toR2) img.setData(bytes);
        img = images.save(img);
        // New pictures go to R2 when it is configured and verified; if R2 fails the bytes stay in the database so the upload still succeeds.
        if (toR2 && !media.storeNew(bid, Kind.PRODUCT_IMAGE, img.getId(), id, bytes, type)) img.setData(bytes);
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
        dropCurrent(p, bid);
        p.setImageUrl(null);
        p.setUpdatedAt(Instant.now());
    }

    /** Deletes the stored bytes of the product's current main picture (only if it is one of ours, this business's, and not a gallery photo), so replaced or removed pictures do not pile up in the database. */
    private void dropCurrent(Product p, UUID bid) {
        String url = p.getImageUrl();
        int at = url == null ? -1 : url.lastIndexOf("/api/public/images/");
        if (at < 0) return;
        try {
            UUID old = UUID.fromString(url.substring(at + "/api/public/images/".length()));
            images.findByIdAndBusinessId(old, bid).filter(i -> !i.isGallery() && p.getId().equals(i.getProductId())).ifPresent(i -> { images.delete(i); media.remove(Kind.PRODUCT_IMAGE, i.getId()); });
        } catch (IllegalArgumentException ignored) { /* an external or malformed URL: nothing of ours to delete */ }
    }

    @GetMapping("/api/public/images/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> serve(@PathVariable UUID id, @RequestParam(value = "size", required = false) String size) {
        var ref = media.verifiedRef(Kind.PRODUCT_IMAGE, id);
        if (ref.isPresent()) return MediaHttp.fromR2(media, ref.get(), size, java.time.Duration.ofDays(1));
        ProductImage img = images.findById(id).orElseThrow(() -> ApiException.notFound("Image not found"));
        if (img.getData() == null) throw ApiException.notFound("Image not found");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.getContentType()))
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic().immutable()).body(img.getData());
    }

    static String sniff(byte[] b) { return ImageProcessor.sniff(b); }
}
