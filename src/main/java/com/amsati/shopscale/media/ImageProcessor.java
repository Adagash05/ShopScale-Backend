package com.amsati.shopscale.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Validates uploads and makes the smaller sizes, with the JDK only. The original file is always kept untouched; sizes are extra.
 * JPEG and PNG are decoded and resized. WebP uploads are accepted (checked by header) and stored as they are: the JDK cannot decode or encode WebP,
 * so no resized copies are made for them, and resized copies of JPEG/PNG are JPEG/PNG, not WebP.
 */
public final class ImageProcessor {
    public static final int THUMB = 320, STANDARD = 800, FULL = 1600;
    /** Refuse pictures that would need an enormous amount of memory to decode (a "decompression bomb"). */
    static final long MAX_PIXELS = 40_000_000L;

    public record Variant(String name, byte[] data, String contentType, int width, int height) {}
    public record Info(String contentType, int width, int height) {}

    private ImageProcessor() {}

    public static String sniff(byte[] b) {
        if (b == null) return null;
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    public static String extension(String contentType) {
        return switch (contentType) { case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp"; default -> throw new IllegalArgumentException("Unsupported type " + contentType); };
    }

    /** Type and dimensions, or null if the bytes are not a usable JPEG/PNG/WebP. JPEG/PNG must really decode; oversized pictures are refused. */
    public static Info inspect(byte[] data) {
        String type = sniff(data);
        if (type == null) return null;
        if (type.equals("image/webp")) return new Info(type, 0, 0);   // dimensions unknown without a WebP decoder
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) return null;
                r.read(0);   // proves the file decodes
                return new Info(type, w, h);
            } finally { r.dispose(); }
        } catch (Exception e) { return null; }
    }

    /** The thumbnail and standard sizes for a decodable JPEG/PNG; empty for WebP and for pictures already smaller than the size. Never upscales. */
    public static java.util.List<Variant> variants(byte[] data, Info info) {
        java.util.List<Variant> out = new java.util.ArrayList<>();
        if (info == null || info.contentType().equals("image/webp")) return out;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return out;
            boolean alpha = src.getColorModel().hasAlpha();
            for (int target : new int[] {THUMB, STANDARD}) {
                if (src.getWidth() <= target) continue;
                int w = target, h = Math.max(1, Math.round(src.getHeight() * (target / (float) src.getWidth())));
                BufferedImage scaled = scale(src, w, h, alpha);
                byte[] bytes = alpha ? png(scaled) : jpeg(scaled, 0.85f);
                out.add(new Variant(target == THUMB ? "thumb" : "standard", bytes, alpha ? "image/png" : "image/jpeg", w, h));
            }
        } catch (Exception ignored) { /* the original is still stored; sizes are an optimisation */ }
        return out;
    }

    /** Halves repeatedly before the last step so large reductions stay sharp. */
    private static BufferedImage scale(BufferedImage src, int w, int h, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = src.getType() == type ? src : redraw(src, src.getWidth(), src.getHeight(), type);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = redraw(cur, cur.getWidth() / 2, cur.getHeight() / 2, type);
        return redraw(cur, w, h, type);
    }

    private static BufferedImage redraw(BufferedImage s, int w, int h, int type) {
        BufferedImage o = new BufferedImage(w, h, type);
        Graphics2D g = o.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (type == BufferedImage.TYPE_INT_RGB) { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h); }
            g.drawImage(s, 0, 0, w, h, null);
        } finally { g.dispose(); }
        return o;
    }

    private static byte[] png(BufferedImage img) throws java.io.IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(img, "png", b); return b.toByteArray(); }

    private static byte[] jpeg(BufferedImage img, float quality) throws java.io.IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream b = new ByteArrayOutputStream(); javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(b)) {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), p);
            ios.flush();
            return b.toByteArray();
        } finally { w.dispose(); }
    }
}
