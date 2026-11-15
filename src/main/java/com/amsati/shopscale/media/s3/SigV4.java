package com.amsati.shopscale.media.s3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for S3-compatible APIs (Cloudflare R2 uses it with region "auto"). JDK only. */
public final class SigV4 {
    private SigV4() {}

    public static String sha256Hex(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] hmac(byte[] key, String data) {
        try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * The Authorization header value. {@code headers} are every header to sign, with lower-case names and trimmed values (must include host,
     * x-amz-content-sha256 and x-amz-date). {@code path} is the already URI-encoded absolute path; {@code query} the already sorted, encoded query string or "".
     */
    public static String authorization(String method, String path, String query, Map<String, String> headers, String payloadSha256, String amzDate,
                                       String region, String service, String accessKey, String secretKey) {
        TreeMap<String, String> h = new TreeMap<>(headers);
        StringBuilder canonHeaders = new StringBuilder(), signed = new StringBuilder();
        for (var e : h.entrySet()) {
            canonHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(e.getKey());
        }
        String canonical = method + "\n" + path + "\n" + query + "\n" + canonHeaders + "\n" + signed + "\n" + payloadSha256;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region); k = hmac(k, service); k = hmac(k, "aws4_request");
        String sig = HexFormat.of().formatHex(hmac(k, toSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + sig;
    }

    /** RFC 3986 encoding of one path segment, as S3 requires (unreserved characters stay, everything else is %XX). */
    public static String encodeSegment(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes(StandardCharsets.UTF_8)) {
            int x = c & 0xff;
            if ((x >= 'A' && x <= 'Z') || (x >= 'a' && x <= 'z') || (x >= '0' && x <= '9') || x == '-' || x == '_' || x == '.' || x == '~') b.append((char) x);
            else b.append('%').append(String.format("%02X", x));
        }
        return b.toString();
    }
}
