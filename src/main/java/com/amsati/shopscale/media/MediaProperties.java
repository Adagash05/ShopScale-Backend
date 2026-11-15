package com.amsati.shopscale.media;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Object-storage settings from the environment. Nothing here is ever logged or returned by an API except the booleans and variable names in {@link #missing()}. */
@Component
public class MediaProperties {
    @Getter @Value("${app.media.mode:AUTO}") private String mode;
    @Getter @Value("${app.media.r2.account-id:}") private String accountId;
    @Getter @Value("${app.media.r2.access-key-id:}") private String accessKeyId;
    @Getter @Value("${app.media.r2.secret-access-key:}") private String secretAccessKey;
    @Getter @Value("${app.media.r2.bucket-name:}") private String bucketName;
    @Value("${app.media.r2.endpoint:}") private String endpointOverride;
    @Getter @Value("${app.media.r2.public-base-url:}") private String publicBaseUrl;
    /** REDIRECT (default): the API answers with a redirect to the CDN address. PROXY: the API streams the object itself. */
    @Value("${app.media.serve-mode:REDIRECT}") private String serveMode;
    public boolean proxy() { return "PROXY".equalsIgnoreCase(serveMode); }

    public String endpoint() {
        if (endpointOverride != null && !endpointOverride.isBlank()) return endpointOverride.trim().replaceAll("/+$", "");
        return accountId == null || accountId.isBlank() ? "" : "https://" + accountId.trim() + ".r2.cloudflarestorage.com";
    }

    /** Names of required variables that are empty. The endpoint is derived from the account id when not given, so it is not "missing" then. */
    public List<String> missing() {
        List<String> m = new ArrayList<>();
        if (blank(accountId) && blank(endpointOverride)) m.add("CLOUDFLARE_R2_ACCOUNT_ID");
        if (blank(accessKeyId)) m.add("CLOUDFLARE_R2_ACCESS_KEY_ID");
        if (blank(secretAccessKey)) m.add("CLOUDFLARE_R2_SECRET_ACCESS_KEY");
        if (blank(bucketName)) m.add("CLOUDFLARE_R2_BUCKET_NAME");
        if (blank(publicBaseUrl)) m.add("CLOUDFLARE_R2_PUBLIC_BASE_URL");
        return m;
    }

    /** Problems with values that are present (not secrets). */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        String ep = endpoint();
        if (!ep.isEmpty() && !ep.startsWith("https://") && !ep.startsWith("http://localhost") && !ep.startsWith("http://127.0.0.1")) p.add("CLOUDFLARE_R2_ENDPOINT must start with https://");
        if (!blank(publicBaseUrl) && !publicBaseUrl.trim().startsWith("https://")) p.add("CLOUDFLARE_R2_PUBLIC_BASE_URL must start with https://");
        if (!blank(bucketName) && !bucketName.trim().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) p.add("CLOUDFLARE_R2_BUCKET_NAME is not a valid bucket name");
        if (!mode.equalsIgnoreCase("AUTO") && !mode.equalsIgnoreCase("DB")) p.add("MEDIA_STORAGE_MODE must be AUTO or DB");
        return p;
    }

    public boolean configured() { return missing().isEmpty() && problems().isEmpty(); }
    /** The operator can force database storage (rollback) even when R2 is configured. */
    public boolean r2Allowed() { return configured() && !mode.equalsIgnoreCase("DB"); }
    public String publicUrl(String key) { return publicBaseUrl.trim().replaceAll("/+$", "") + "/" + key; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
