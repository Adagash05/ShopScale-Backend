package com.amsati.shopscale.team;

/** Branded account emails (plain-text and HTML). Names and links are escaped; the link is the only thing a recipient needs. */
public final class EmailTemplates {
    public record Mail(String subject, String text, String html) {}

    private static final String BRAND = "#4f46e5";

    private EmailTemplates() {}

    public static Mail verification(String name, String link) {
        String text = "Hello " + clean(name) + ",\n\nWelcome to ShopScale. Confirm your email address to finish setting up your account:\n\n" + link
            + "\n\nThis link works for 3 days and can be used once. If you did not create a ShopScale account, you can ignore this email.\n\n- The ShopScale team";
        String html = layout("Confirm your email",
            "<p>Hello " + esc(name) + ",</p><p>Welcome to ShopScale. Confirm your email address to finish setting up your account.</p>"
                + button(link, "Confirm email")
                + "<p style=\"color:#6b7280;font-size:13px\">This link works for 3 days and can be used once. If you did not create a ShopScale account, you can ignore this email.</p>"
                + fallback(link));
        return new Mail("Confirm your ShopScale email", text, html);
    }

    public static Mail passwordReset(String name, String link) {
        String text = "Hello " + clean(name) + ",\n\nUse this link within one hour to choose a new password:\n\n" + link
            + "\n\nIf you did not ask for this, ignore this email; your password has not changed.\n\n- The ShopScale team";
        String html = layout("Reset your password",
            "<p>Hello " + esc(name) + ",</p><p>Use the button below within one hour to choose a new password.</p>"
                + button(link, "Choose a new password")
                + "<p style=\"color:#6b7280;font-size:13px\">If you did not ask for this, ignore this email. Your password has not changed.</p>"
                + fallback(link));
        return new Mail("Reset your ShopScale password", text, html);
    }

    private static String button(String link, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"" + esc(link) + "\" style=\"background:" + BRAND
            + ";color:#ffffff;text-decoration:none;padding:12px 22px;border-radius:8px;font-weight:600;display:inline-block\">" + esc(label) + "</a></p>";
    }

    private static String fallback(String link) {
        return "<p style=\"color:#6b7280;font-size:12px;word-break:break-all\">Button not working? Copy this link into your browser:<br>" + esc(link) + "</p>";
    }

    private static String layout(String heading, String body) {
        return "<!doctype html><html><body style=\"margin:0;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#111827\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
            + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden\">"
            + "<tr><td style=\"background:" + BRAND + ";color:#ffffff;padding:18px 28px;font-size:20px;font-weight:700\">ShopScale</td></tr>"
            + "<tr><td style=\"padding:28px;font-size:15px;line-height:1.55\"><h1 style=\"margin:0 0 16px;font-size:20px\">" + esc(heading) + "</h1>" + body + "</td></tr>"
            + "<tr><td style=\"padding:16px 28px;background:#f9fafb;color:#6b7280;font-size:12px\">Sent by ShopScale (AMSATI). Please do not reply to this message.</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    /** Names go into a plain-text body: no line breaks that could add headers or spoof content. */
    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim(); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
