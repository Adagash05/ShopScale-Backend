package com.amsati.shopscale.media;

import java.util.UUID;

/** Object keys. The business id always comes from the server (the authenticated tenant or the database row), never from a request. */
public final class MediaKeys {
    private MediaKeys() {}

    public enum Kind { PRODUCT_IMAGE, STORE_LOGO, INVOICE_LOGO }

    /** merchants/{businessId}/products/{productId}/{imageId}.{ext} */
    public static String product(UUID businessId, UUID productId, UUID imageId, String contentType) {
        return "merchants/" + businessId + "/products/" + productId + "/" + imageId + "." + ImageProcessor.extension(contentType);
    }

    public static String variant(String originalKey, int width) {
        int dot = originalKey.lastIndexOf('.');
        return originalKey.substring(0, dot) + "_" + width + originalKey.substring(dot);
    }

    /** The logo key contains a short content hash so a replaced logo gets a new URL and CDN caches never serve a stale one. */
    public static String logo(UUID businessId, Kind kind, String sha256, String contentType) {
        String folder = kind == Kind.STORE_LOGO ? "store" : "invoices";
        return "merchants/" + businessId + "/" + folder + "/logo-" + sha256.substring(0, 12) + "." + ImageProcessor.extension(contentType);
    }

    /** True only for keys inside this business's own folder. */
    public static boolean belongsTo(UUID businessId, String key) { return key != null && key.startsWith("merchants/" + businessId + "/") && !key.contains(".."); }
}
