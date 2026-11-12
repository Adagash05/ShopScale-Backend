package com.amsati.shopscale.invoice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small, dependency-free PDF 1.4 writer: A4 pages, the built-in Helvetica fonts, filled rectangles, lines and JPEG images.
 * Only what an invoice needs. Text is WinAnsi (Latin-1): anything outside it is replaced with '?' so a file is never corrupt.
 */
public final class SimplePdf {
    public static final float W = 595.28f, H = 841.89f;

    // Helvetica / Helvetica-Bold advance widths for ASCII 32..126 (per 1000 em), from the Adobe core font metrics.
    private static final int[] REG = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015,
        667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
        278, 278, 278, 469, 556, 333,
        556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500,
        334, 260, 334, 584};
    private static final int[] BOLD = {278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975,
        722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
        333, 278, 333, 584, 556, 333,
        556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500,
        389, 280, 389, 584};

    public static float width(String s, boolean bold, float size) {
        int[] t = bold ? BOLD : REG;
        float w = 0;
        for (char c : latin1(s).toCharArray()) w += (c >= 32 && c <= 126 ? t[c - 32] : 556);
        return w * size / 1000f;
    }

    /** Wraps text to a width (points). Never returns an empty list. */
    public static List<String> wrap(String text, boolean bold, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String para : String.valueOf(text == null ? "" : text).split("\\R", -1)) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = cur.length() == 0 ? word : cur + " " + word;
                if (width(trial, bold, size) <= maxWidth || cur.length() == 0) {
                    cur = new StringBuilder(trial);
                    while (width(cur.toString(), bold, size) > maxWidth && cur.length() > 1) {   // a single very long word
                        int cut = cur.length() - 1;
                        while (cut > 1 && width(cur.substring(0, cut), bold, size) > maxWidth) cut--;
                        lines.add(cur.substring(0, cut));
                        cur = new StringBuilder(cur.substring(cut));
                    }
                } else { lines.add(cur.toString()); cur = new StringBuilder(word); }
            }
            lines.add(cur.toString());
        }
        return lines;
    }

    static String latin1(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : (c >= 32 && c <= 126) || (c >= 161 && c <= 255) ? c : '?');
        return b.toString();
    }

    private static String esc(String s) {
        return latin1(s).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    public static final class Page {
        private final StringBuilder c = new StringBuilder();
        private final List<int[]> images = new ArrayList<>();   // {imageIndex}
        private final List<float[]> imagePlacements = new ArrayList<>();

        private static String n(float v) { return String.format(Locale.US, "%.2f", v); }

        /** Colour as grey 0..1 or rgb. */
        public Page fill(float r, float g, float b) { c.append(n(r)).append(' ').append(n(g)).append(' ').append(n(b)).append(" rg\n"); return this; }
        public Page stroke(float r, float g, float b) { c.append(n(r)).append(' ').append(n(g)).append(' ').append(n(b)).append(" RG\n"); return this; }
        public Page rect(float x, float yTop, float w, float h) { c.append(n(x)).append(' ').append(n(H - yTop - h)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n"); return this; }
        public Page line(float x1, float y1, float x2, float y2, float lw) {
            c.append(n(lw)).append(" w ").append(n(x1)).append(' ').append(n(H - y1)).append(" m ").append(n(x2)).append(' ').append(n(H - y2)).append(" l S\n"); return this;
        }
        /** y is the text baseline measured from the TOP of the page. */
        public Page text(float x, float y, String s, boolean bold, float size) {
            c.append("BT /").append(bold ? "F2" : "F1").append(' ').append(n(size)).append(" Tf ").append(n(x)).append(' ').append(n(H - y)).append(" Td (").append(esc(s)).append(") Tj ET\n");
            return this;
        }
        public Page textRight(float xRight, float y, String s, boolean bold, float size) { return text(xRight - width(s, bold, size), y, s, bold, size); }
        public Page image(int imageIndex, float x, float yTop, float w, float h) {
            imagePlacements.add(new float[] {imageIndex, x, H - yTop - h, w, h});
            return this;
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private final List<byte[]> jpegs = new ArrayList<>();
    private final List<int[]> jpegSizes = new ArrayList<>();
    private String title = "";

    public SimplePdf title(String t) { this.title = t; return this; }
    public Page addPage() { Page p = new Page(); pages.add(p); return p; }
    public int pageCount() { return pages.size(); }

    /** Adds "Invoice X  |  Page n of N" and the generator line at the bottom of every page. */
    public SimplePdf withFooters(String label, int total) {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            p.stroke(0.89f, 0.91f, 0.94f).line(40, H - 44, W - 40, H - 44, 0.8f);
            p.fill(0.58f, 0.64f, 0.72f);
            p.text(40, H - 30, label + "  |  Page " + (i + 1) + " of " + total, false, 8);
            p.textRight(W - 40, H - 30, "Created with ShopScale", false, 8);
        }
        return this;
    }

    /** Registers a baseline JPEG (RGB) and returns its index. */
    public int addJpeg(byte[] jpeg, int pxW, int pxH) { jpegs.add(jpeg); jpegSizes.add(new int[] {pxW, pxH}); return jpegs.size() - 1; }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        // object numbering: 1 catalog, 2 pages, 3 F1, 4 F2, 5 info, then images, then (page, content) pairs
        int imgBase = 6;
        int pageBase = imgBase + jpegs.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) kids.append(pageBase + i * 2).append(" 0 R ");
        obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offsets, 2, "<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
        obj(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, 5, "<< /Title (" + esc(title) + ") /Producer (ShopScale) >>");
        for (int i = 0; i < jpegs.size(); i++) {
            offsets.add(out.size());
            write(out, (imgBase + i) + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + jpegSizes.get(i)[0] + " /Height " + jpegSizes.get(i)[1]
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpegs.get(i).length + " >>\nstream\n");
            out.writeBytes(jpegs.get(i));
            write(out, "\nendstream\nendobj\n");
        }
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            StringBuilder content = new StringBuilder(p.c);
            StringBuilder xobjs = new StringBuilder();
            for (float[] pl : p.imagePlacements) {
                int idx = (int) pl[0];
                content.append(String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im%d Do Q\n", pl[3], pl[4], pl[1], pl[2], idx));
                xobjs.append("/Im").append(idx).append(' ').append(imgBase + idx).append(" 0 R ");
            }
            int po = pageBase + i * 2;
            obj(out, offsets, po, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + String.format(Locale.US, "%.2f %.2f", W, H) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>"
                + (xobjs.length() > 0 ? " /XObject << " + xobjs + ">>" : "") + " >> /Contents " + (po + 1) + " 0 R >>");
            byte[] data = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            offsets.add(out.size());
            write(out, (po + 1) + " 0 obj\n<< /Length " + data.length + " >>\nstream\n");
            out.writeBytes(data);
            write(out, "\nendstream\nendobj\n");
        }
        // objects were appended in numeric order, so offsets[i] belongs to object i+1
        int xref = out.size();
        write(out, "xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) write(out, String.format(Locale.US, "%010d 00000 n \n", o));
        write(out, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R /Info 5 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void obj(ByteArrayOutputStream out, List<Integer> offsets, int n, String body) {
        offsets.add(out.size());
        write(out, n + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream out, String s) { out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }
}
