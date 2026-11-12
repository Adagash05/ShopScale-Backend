package com.amsati.shopscale.payment;

import com.amsati.shopscale.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thin Paystack REST client. The caller passes the secret key to use, which is how the same code serves two separate
 * accounts: AMSATI's own key for ShopScale subscriptions, and each merchant's key for that merchant's store.
 */
@Slf4j
@Component
public class PaystackClient {
    private static final String BASE = "https://api.paystack.co";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public record Init(String authorizationUrl, String reference) {}
    public record Verification(boolean success, String status, long amountKobo, String currency, String reference, String customerEmail, String authorizationCode, JsonNode raw) {}

    private JsonNode call(String secret, String method, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json");
            b = "POST".equals(method) ? b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))) : b.GET();
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(res.body());
            if (res.statusCode() == 401) throw ApiException.badRequest("Paystack did not accept this secret key. Check it and try again.");
            if (res.statusCode() >= 400 || !node.path("status").asBoolean(false)) {
                throw ApiException.badRequest("Paystack: " + node.path("message").asText("request failed"));
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Paystack call failed: {}", e.getClass().getSimpleName());
            throw ApiException.conflict("Could not reach Paystack. Try again in a moment.");
        }
    }

    /** Cheap authenticated call that proves a secret key is valid. */
    public void checkKey(String secret) { call(secret, "GET", "/bank?perPage=1", null); }

    public Init initialize(String secret, String email, long amountKobo, String reference, String callbackUrl, Map<String, Object> metadata) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", amountKobo);
        body.put("currency", "NGN");
        body.put("reference", reference);
        body.put("callback_url", callbackUrl);
        if (metadata != null) body.put("metadata", metadata);
        JsonNode d = call(secret, "POST", "/transaction/initialize", body).path("data");
        return new Init(d.path("authorization_url").asText(), d.path("reference").asText(reference));
    }

    public Verification verify(String secret, String reference) {
        JsonNode d = call(secret, "GET", "/transaction/verify/" + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8), null).path("data");
        return toVerification(d);
    }

    /** Result of POST /transaction/charge_authorization. 'paused' = the card needs the customer to authenticate (2FA); nothing was charged yet. */
    public record Charge(String status, boolean paused, String authorizationUrl, String reference, String message, JsonNode raw) {}

    /** Like verify, but returns null when Paystack has no such transaction (it was never created). Network errors still throw. */
    public Verification find(String secret, String reference) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/transaction/verify/" + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + secret).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return null;
            JsonNode node = json.readTree(res.body());
            if (res.statusCode() == 401) throw ApiException.badRequest("Paystack did not accept this secret key. Check it and try again.");
            if (res.statusCode() >= 400 || !node.path("status").asBoolean(false)) {
                if (node.path("message").asText("").toLowerCase().contains("not found")) return null;
                throw ApiException.badRequest("Paystack: " + node.path("message").asText("request failed"));
            }
            return toVerification(node.path("data"));
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ApiException.conflict("Could not reach Paystack. Try again in a moment.");
        }
    }

    /**
     * Charge a saved reusable authorization (recurring billing). Only the email that created the authorization can charge it.
     * A network failure throws a 409 ApiException: the caller must treat the outcome as UNKNOWN (never retry with a new reference).
     * A 4xx answer from Paystack throws a 400 ApiException: Paystack refused, nothing was charged.
     */
    public Charge chargeAuthorization(String secret, String email, long amountKobo, String reference, String authorizationCode, String callbackUrl, Map<String, Object> metadata) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("authorization_code", authorizationCode);
        body.put("email", email);
        body.put("amount", amountKobo);
        body.put("currency", "NGN");
        body.put("reference", reference);
        if (callbackUrl != null) body.put("callback_url", callbackUrl);
        if (metadata != null) body.put("metadata", metadata);
        JsonNode node = call(secret, "POST", "/transaction/charge_authorization", body);
        JsonNode d = node.path("data");
        return new Charge(d.path("status").asText(""), d.path("paused").asBoolean(false), d.path("authorization_url").asText(""), d.path("reference").asText(reference), node.path("message").asText(""), d);
    }

    public Verification toVerification(JsonNode d) {
        String status = d.path("status").asText("");
        return new Verification("success".equals(status), status, d.path("amount").asLong(0), d.path("currency").asText(""), d.path("reference").asText(""),
            d.path("customer").path("email").asText(""), d.path("authorization").path("authorization_code").asText(""), d);
    }

    /** Paystack signs the raw webhook body with HMAC-SHA512 using the account's secret key. Constant-time comparison. */
    public boolean validSignature(String secret, String rawBody, String signatureHeader) {
        return rawBody != null && validSignature(secret, rawBody.getBytes(StandardCharsets.UTF_8), signatureHeader);
    }

    /** Same check over the exact bytes Paystack sent, so no character-set decoding can change what was signed. */
    public boolean validSignature(String secret, byte[] rawBody, String signatureHeader) {
        if (secret == null || secret.isBlank() || rawBody == null || signatureHeader == null || signatureHeader.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = mac.doFinal(rawBody);
            StringBuilder hex = new StringBuilder();
            for (byte x : expected) hex.append(String.format("%02x", x));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }

    public static long toKobo(BigDecimal naira) { return naira.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(); }

    public JsonNode parse(byte[] body) {
        try { return json.readTree(body); } catch (java.io.IOException e) { throw ApiException.badRequest("Invalid payload"); }
    }

    public JsonNode parse(String body) {
        try { return json.readTree(body); } catch (java.io.IOException e) { throw ApiException.badRequest("Invalid payload"); }
    }
}
