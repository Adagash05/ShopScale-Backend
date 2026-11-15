package com.amsati.shopscale.media.s3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Minimal S3-compatible client for Cloudflare R2: put, head, get, delete, path-style, SigV4, JDK HttpClient only (no new dependency).
 * Errors carry the HTTP status and the provider's error code, never the credentials or the signed headers.
 */
public final class R2Client {
    public record ObjectInfo(long size, String etag, String contentType) {}

    public static final class StorageException extends RuntimeException {
        private final int status;
        public StorageException(String message, int status) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final URI endpoint;
    private final String bucket, accessKey, secretKey, region;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public R2Client(String endpoint, String bucket, String accessKey, String secretKey) { this(endpoint, bucket, accessKey, secretKey, "auto"); }

    public R2Client(String endpoint, String bucket, String accessKey, String secretKey, String region) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.bucket = bucket; this.accessKey = accessKey; this.secretKey = secretKey; this.region = region;
    }

    private String path(String key) {
        StringBuilder b = new StringBuilder("/").append(SigV4.encodeSegment(bucket));
        for (String seg : key.split("/")) b.append('/').append(SigV4.encodeSegment(seg));
        return b.toString();
    }

    private HttpResponse<byte[]> call(String method, String key, byte[] body, String contentType) { return call(method, key, "", body, contentType); }

    private HttpResponse<byte[]> call(String method, String key, String query, byte[] body, String contentType) {
        String path = key == null ? "/" + SigV4.encodeSegment(bucket) : path(key);
        String payload = SigV4.sha256Hex(body == null ? new byte[0] : body);
        String amzDate = AMZ.format(Instant.now());
        int port = endpoint.getPort();
        String host = endpoint.getHost() + (port > 0 && port != 443 && port != 80 ? ":" + port : "");
        TreeMap<String, String> h = new TreeMap<>();
        h.put("host", host); h.put("x-amz-content-sha256", payload); h.put("x-amz-date", amzDate);
        if (contentType != null) h.put("content-type", contentType);
        String auth = SigV4.authorization(method, path, query, h, payload, amzDate, region, "s3", accessKey, secretKey);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint.getScheme() + "://" + host + path + (query.isEmpty() ? "" : "?" + query))).timeout(Duration.ofSeconds(30))
            .header("x-amz-content-sha256", payload).header("x-amz-date", amzDate).header("Authorization", auth);
        if (contentType != null) rb.header("Content-Type", contentType);
        HttpRequest.BodyPublisher bp = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        try { return http.send(rb.method(method, bp).build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (IOException e) { throw new StorageException("Could not reach object storage: " + e.getClass().getSimpleName(), 0); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StorageException("Interrupted while contacting object storage", 0); }
    }

    private static StorageException fail(String what, HttpResponse<byte[]> r) {
        String body = new String(r.body() == null ? new byte[0] : r.body(), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Code>([^<]{1,60})</Code>").matcher(body);
        return new StorageException(what + " failed (HTTP " + r.statusCode() + (m.find() ? ", " + m.group(1) : "") + ")", r.statusCode());
    }

    public void put(String key, byte[] data, String contentType) {
        HttpResponse<byte[]> r = call("PUT", key, data, contentType);
        if (r.statusCode() / 100 != 2) throw fail("Upload", r);
    }

    public Optional<ObjectInfo> head(String key) {
        HttpResponse<byte[]> r = call("HEAD", key, null, null);
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() / 100 != 2) throw fail("Check", r);
        return Optional.of(new ObjectInfo(r.headers().firstValueAsLong("content-length").orElse(-1), r.headers().firstValue("etag").orElse(null), r.headers().firstValue("content-type").orElse(null)));
    }

    public Optional<byte[]> get(String key) {
        HttpResponse<byte[]> r = call("GET", key, null, null);
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() / 100 != 2) throw fail("Download", r);
        return Optional.of(r.body());
    }

    /** Deleting a missing object is not an error (S3 semantics). */
    public void delete(String key) {
        HttpResponse<byte[]> r = call("DELETE", key, null, null);
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) throw fail("Delete", r);
    }

    public record Listed(java.util.List<String> keys, java.util.List<Long> sizes, String nextToken) {}

    /** One page (up to {@code max} keys, at most 1000) of ListObjectsV2 under {@code prefix}; pass the returned token to continue, null to start. */
    public Listed list(String prefix, String continuationToken, int max) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("list-type", "2"); q.put("max-keys", String.valueOf(Math.min(Math.max(max, 1), 1000)));
        if (prefix != null && !prefix.isEmpty()) q.put("prefix", prefix);
        if (continuationToken != null) q.put("continuation-token", continuationToken);
        StringBuilder qs = new StringBuilder();
        for (var e : q.entrySet()) { if (qs.length() > 0) qs.append('&'); qs.append(SigV4.encodeSegment(e.getKey())).append('=').append(SigV4.encodeSegment(e.getValue())); }
        HttpResponse<byte[]> r = call("GET", null, qs.toString(), null, null);
        if (r.statusCode() / 100 != 2) throw fail("List", r);
        String xml = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<String> keys = new java.util.ArrayList<>(); java.util.List<Long> sizes = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Contents>(.*?)</Contents>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String c = m.group(1);
            java.util.regex.Matcher k = java.util.regex.Pattern.compile("<Key>(.*?)</Key>", java.util.regex.Pattern.DOTALL).matcher(c);
            java.util.regex.Matcher z = java.util.regex.Pattern.compile("<Size>(\\d+)</Size>").matcher(c);
            if (k.find()) { keys.add(unxml(k.group(1))); sizes.add(z.find() ? Long.parseLong(z.group(1)) : 0L); }
        }
        java.util.regex.Matcher n = java.util.regex.Pattern.compile("<NextContinuationToken>(.*?)</NextContinuationToken>").matcher(xml);
        return new Listed(keys, sizes, n.find() ? unxml(n.group(1)) : null);
    }

    private static String unxml(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"); }
}
