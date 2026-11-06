package com.amsati.shopscale.business;

import java.util.Set;
import java.util.regex.Pattern;

/** Slugs become part of shop.amsati.com/{slug}, so they must be URL-safe and must not shadow app routes. */
public final class SlugRules {
    private static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$");
    private static final Set<String> RESERVED = Set.of(
        "admin", "api", "app", "www", "login", "register", "signup", "signin", "logout", "onboarding",
        "dashboard", "settings", "static", "assets", "support", "help", "billing", "pricing", "store",
        "shop", "amsati", "shopscale", "health", "swagger-ui", "docs", "mail", "cdn", "auth", "sell",
        "products", "customers", "expenses", "more", "home", "sales", "orders");

    private SlugRules() {}

    public static String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    public static boolean isValidFormat(String slug) { return VALID.matcher(slug).matches() && !slug.contains("--"); }

    public static boolean isReserved(String slug) { return RESERVED.contains(slug); }
}
