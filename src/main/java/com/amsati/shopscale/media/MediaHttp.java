package com.amsati.shopscale.media;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Response shapes for images. Every one carries nosniff; bodies are only images, so scripts in them cannot run. */
public final class MediaHttp {
    private MediaHttp() {}

    public static ResponseEntity<byte[]> bytes(String contentType, byte[] data, Duration cache) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
            .cacheControl(CacheControl.maxAge(cache).cachePublic()).body(data);
    }

    /** From R2: a redirect to the public CDN address, or the bytes themselves in PROXY mode. */
    public static ResponseEntity<byte[]> fromR2(MediaService media, MediaService.Ref ref, String size, Duration cache) {
        if (!media.properties().proxy()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(media.url(ref, size)))
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.maxAge(cache).cachePublic()).build();
        }
        String key = "thumb".equals(size) && ref.thumbKey() != null ? ref.thumbKey() : "standard".equals(size) && ref.standardKey() != null ? ref.standardKey() : ref.objectKey();
        byte[] b = media.bytesOfKey(key).orElse(null);
        if (b == null) return ResponseEntity.notFound().build();
        String type = key.equals(ref.objectKey()) ? ref.contentType() : ImageProcessor.sniff(b) == null ? ref.contentType() : ImageProcessor.sniff(b);
        return bytes(type, b, cache);
    }
}
