package com.amsati.shopscale.invoice;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Lays an invoice out as a printable A4 PDF. */
public final class InvoicePdf {
    private InvoicePdf() {}

    public record Line(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate, BigDecimal amount) {}
    public record View(String businessName, String businessAddress, String businessPhone, String businessEmail, byte[] logo,
                       String number, String status, LocalDate issueDate, LocalDate dueDate, String paymentTerms,
                       String customerName, String customerPhone, String customerEmail, String customerAddress,
                       List<Line> lines, BigDecimal subtotal, BigDecimal tax, BigDecimal invoiceDiscount, BigDecimal total,
                       BigDecimal paid, BigDecimal balance, String paymentInstructions, String note) {}

    private static final float M = 40, RIGHT = SimplePdf.W - 40, BOTTOM = SimplePdf.H - 60;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** "NGN 12,345.00": the naira sign is not in the standard PDF fonts, so the currency code is used. */
    static String money(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return "NGN " + f.format(v == null ? BigDecimal.ZERO : v);
    }

    private static String qty(BigDecimal q) { return q.stripTrailingZeros().toPlainString(); }

    public static String statusLabel(String s) {
        return switch (s) { case "PARTIALLY_PAID" -> "PARTLY PAID"; default -> s.replace('_', ' '); };
    }

    public static byte[] render(View v) {
        SimplePdf pdf = new SimplePdf().title("Invoice " + v.number());
        SimplePdf.Page p = pdf.addPage();
        float y = M;
        int logoIdx = -1;
        float textX = M;
        if (v.logo() != null && v.logo().length > 0) {
            try {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(v.logo()));
                if (src != null) {
                    BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var g = rgb.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); g.drawImage(src, 0, 0, null); g.dispose();
                    ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                    ImageIO.write(rgb, "jpg", jpg);
                    logoIdx = pdf.addJpeg(jpg.toByteArray(), rgb.getWidth(), rgb.getHeight());
                    float box = 56, ratio = (float) rgb.getWidth() / rgb.getHeight();
                    float w = ratio >= 1 ? box : box * ratio, h = ratio >= 1 ? box / ratio : box;
                    p.image(logoIdx, M, y, w, h);
                    textX = M + w + 12;
                }
            } catch (Exception ignored) { /* an unreadable logo is skipped; the invoice still prints */ }
        }
        p.fill(0.09f, 0.13f, 0.2f);
        p.text(textX, y + 16, v.businessName(), true, 16);
        float by = y + 30;
        p.fill(0.39f, 0.45f, 0.55f);
        for (String l : contactLines(v)) { for (String w : SimplePdf.wrap(l, false, 9, 300)) { p.text(textX, by, w, false, 9); by += 12; } }
        p.fill(0.145f, 0.388f, 0.922f);
        p.textRight(RIGHT, y + 20, "INVOICE", true, 24);
        p.fill(0.09f, 0.13f, 0.2f);
        p.textRight(RIGHT, y + 38, v.number(), true, 11);
        p.fill(0.39f, 0.45f, 0.55f);
        p.textRight(RIGHT, y + 52, statusLabel(v.status()), true, 10);
        y = Math.max(by, y + 66) + 14;
        p.stroke(0.89f, 0.91f, 0.94f); p.line(M, y, RIGHT, y, 1);
        y += 18;

        // bill to / dates
        p.fill(0.39f, 0.45f, 0.55f); p.text(M, y, "BILL TO", true, 8);
        p.fill(0.09f, 0.13f, 0.2f);
        float cy = y + 14;
        p.text(M, cy, v.customerName(), true, 11); cy += 14;
        p.fill(0.2f, 0.25f, 0.33f);
        for (String l : new String[] {v.customerPhone(), v.customerEmail(), v.customerAddress()}) {
            if (l == null || l.isBlank()) continue;
            for (String w : SimplePdf.wrap(l, false, 9.5f, 240)) { p.text(M, cy, w, false, 9.5f); cy += 12; }
        }
        float dx = 360, dy = y;
        String[][] meta = {{"Issue date", v.issueDate().format(DATE)}, {"Due date", v.dueDate() == null ? "On receipt" : v.dueDate().format(DATE)},
            {"Terms", v.paymentTerms() == null || v.paymentTerms().isBlank() ? "-" : v.paymentTerms()}};
        for (String[] m : meta) {
            p.fill(0.39f, 0.45f, 0.55f); p.text(dx, dy + 2, m[0], false, 9);
            p.fill(0.09f, 0.13f, 0.2f); p.textRight(RIGHT, dy + 2, SimplePdf.wrap(m[1], false, 9.5f, 130).get(0), true, 9.5f);
            dy += 15;
        }
        y = Math.max(cy, dy) + 16;

        // items
        float cDesc = M + 6, cQty = 318, cPrice = 388, cDisc = 440, cTax = 468, cAmt = RIGHT - 4;
        y = header(p, y, cDesc, cQty, cPrice, cDisc, cTax, cAmt);
        for (Line l : v.lines()) {
            List<String> desc = SimplePdf.wrap(l.description(), false, 9.5f, cQty - cDesc - 40);
            float rowH = Math.max(1, desc.size()) * 12 + 8;
            if (y + rowH > BOTTOM - 120) {
                p = pdf.addPage();
                y = header(p, M, cDesc, cQty, cPrice, cDisc, cTax, cAmt);
            }
            p.fill(0.09f, 0.13f, 0.2f);
            float ty = y + 12;
            for (String d : desc) { p.text(cDesc, ty, d, false, 9.5f); ty += 12; }
            p.textRight(cQty + 30, y + 12, qty(l.quantity()), false, 9.5f);
            p.textRight(cPrice + 38, y + 12, money(l.unitPrice()).substring(4), false, 9.5f);
            p.textRight(cDisc + 20, y + 12, l.discount().signum() > 0 ? money(l.discount()).substring(4) : "-", false, 9.5f);
            p.textRight(cTax + 22, y + 12, l.taxRate().signum() > 0 ? l.taxRate().stripTrailingZeros().toPlainString() + "%" : "-", false, 9.5f);
            p.textRight(cAmt, y + 12, money(l.amount()).substring(4), true, 9.5f);
            y += rowH;
            p.stroke(0.93f, 0.95f, 0.97f); p.line(M, y, RIGHT, y, 0.6f);
        }
        y += 14;
        if (y > BOTTOM - 130) { p = pdf.addPage(); y = M; }

        // totals
        float lx = 330;
        String[][] rows = new String[6][];
        int n = 0;
        rows[n++] = new String[] {"Subtotal", money(v.subtotal()), "n"};
        if (v.tax().signum() > 0) rows[n++] = new String[] {"Tax", money(v.tax()), "n"};
        if (v.invoiceDiscount().signum() > 0) rows[n++] = new String[] {"Discount", "-" + money(v.invoiceDiscount()), "n"};
        rows[n++] = new String[] {"Total", money(v.total()), "b"};
        rows[n++] = new String[] {"Amount paid", money(v.paid()), "n"};
        for (int i = 0; i < n; i++) {
            boolean b = "b".equals(rows[i][2]);
            p.fill(0.2f, 0.25f, 0.33f); p.text(lx, y + 12, rows[i][0], b, b ? 11 : 9.5f);
            p.fill(0.09f, 0.13f, 0.2f); p.textRight(RIGHT, y + 12, rows[i][1], b, b ? 11 : 9.5f);
            y += 18;
        }
        p.fill(0.937f, 0.965f, 1f); p.rect(lx - 8, y, RIGHT - lx + 16, 28);
        p.fill(0.114f, 0.306f, 0.847f); p.text(lx, y + 18, "Balance due", true, 11.5f); p.textRight(RIGHT, y + 18, money(v.balance()), true, 12);
        y += 46;

        for (String[] block : new String[][] {{"PAYMENT INSTRUCTIONS", v.paymentInstructions()}, {"NOTES", v.note()}}) {
            if (block[1] == null || block[1].isBlank()) continue;
            List<String> lines = SimplePdf.wrap(block[1], false, 9.5f, RIGHT - M);
            if (y + 16 + lines.size() * 12 > BOTTOM) { p = pdf.addPage(); y = M; }
            p.fill(0.39f, 0.45f, 0.55f); p.text(M, y, block[0], true, 8);
            p.fill(0.2f, 0.25f, 0.33f); y += 14;
            for (String l : lines) { p.text(M, y, l, false, 9.5f); y += 12; }
            y += 12;
        }
        // footers (page x of y) once the page count is known
        int total = pdf.pageCount();
        for (int i = 0; i < total; i++) { /* pages are built in order; footer text added below */ }
        return finish(pdf, v, total);
    }

    private static byte[] finish(SimplePdf pdf, View v, int total) {
        // The footer is added by re-opening each page through the builder's page list.
        return pdf.withFooters(v.number(), total).build();
    }

    private static float header(SimplePdf.Page p, float y, float cDesc, float cQty, float cPrice, float cDisc, float cTax, float cAmt) {
        p.fill(0.945f, 0.961f, 0.976f); p.rect(M, y, RIGHT - M, 22);
        p.fill(0.28f, 0.33f, 0.41f);
        float ty = y + 14.5f;
        p.text(cDesc, ty, "DESCRIPTION", true, 8);
        p.textRight(cQty + 30, ty, "QTY", true, 8);
        p.textRight(cPrice + 38, ty, "PRICE", true, 8);
        p.textRight(cDisc + 20, ty, "DISC.", true, 8);
        p.textRight(cTax + 22, ty, "TAX", true, 8);
        p.textRight(cAmt, ty, "AMOUNT", true, 8);
        return y + 22;
    }

    private static List<String> contactLines(View v) {
        List<String> l = new java.util.ArrayList<>();
        if (v.businessAddress() != null && !v.businessAddress().isBlank()) l.add(v.businessAddress());
        String c = ((v.businessPhone() == null ? "" : v.businessPhone()) + (v.businessPhone() != null && v.businessEmail() != null && !v.businessEmail().isBlank() ? "  |  " : "")
            + (v.businessEmail() == null ? "" : v.businessEmail())).trim();
        if (!c.isEmpty()) l.add(c);
        return l;
    }
}
