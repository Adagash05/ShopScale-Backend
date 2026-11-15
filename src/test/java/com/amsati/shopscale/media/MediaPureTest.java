package com.amsati.shopscale.media;

import static org.junit.jupiter.api.Assertions.*;

import com.amsati.shopscale.media.MediaKeys.Kind;
import com.amsati.shopscale.media.s3.SigV4;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MediaPureTest {
    private static byte[] encode(BufferedImage i, String fmt) throws Exception { ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(i, fmt, b); return b.toByteArray(); }

    @Test
    void sigV4MatchesTheAwsGetExample() {
        TreeMap<String, String> h = new TreeMap<>(Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
            "x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "x-amz-date", "20130524T000000Z"));
        String auth = SigV4.authorization("GET", "/test.txt", "", h, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "20130524T000000Z", "us-east-1", "s3",
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertTrue(auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
    }

    @Test
    void keysAreTenantAwareAndStayInsideTheBusinessFolder() {
        UUID b = UUID.randomUUID(), p = UUID.randomUUID(), i = UUID.randomUUID();
        String k = MediaKeys.product(b, p, i, "image/jpeg");
        assertEquals("merchants/" + b + "/products/" + p + "/" + i + ".jpg", k);
        assertEquals("merchants/" + b + "/products/" + p + "/" + i + "_320.jpg", MediaKeys.variant(k, 320));
        assertTrue(MediaKeys.belongsTo(b, k));
        assertFalse(MediaKeys.belongsTo(UUID.randomUUID(), k));
        assertFalse(MediaKeys.belongsTo(b, "merchants/" + b + "/../other/x.jpg"));
        assertTrue(MediaKeys.logo(b, Kind.STORE_LOGO, "a".repeat(64), "image/png").startsWith("merchants/" + b + "/store/logo-"));
        assertTrue(MediaKeys.logo(b, Kind.INVOICE_LOGO, "a".repeat(64), "image/png").startsWith("merchants/" + b + "/invoices/logo-"));
    }

    @Test
    void imagesAreCheckedByContentAndResizedWithoutUpscaling() throws Exception {
        BufferedImage big = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        byte[] jpg = encode(big, "jpg");
        var info = ImageProcessor.inspect(jpg);
        assertNotNull(info);
        var v = ImageProcessor.variants(jpg, info);
        assertEquals(2, v.size());
        assertEquals(320, v.get(0).width());
        assertEquals(800, v.get(1).width());
        byte[] small = encode(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png");
        assertTrue(ImageProcessor.variants(small, ImageProcessor.inspect(small)).isEmpty());
        assertNull(ImageProcessor.inspect("<html>not an image</html>".getBytes()));
        assertNull(ImageProcessor.inspect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}));
    }

    private static MediaProperties props(String mode, String account, String key, String secret, String bucket, String base) {
        MediaProperties p = new MediaProperties();
        ReflectionTestUtils.setField(p, "mode", mode); ReflectionTestUtils.setField(p, "accountId", account); ReflectionTestUtils.setField(p, "accessKeyId", key);
        ReflectionTestUtils.setField(p, "secretAccessKey", secret); ReflectionTestUtils.setField(p, "bucketName", bucket); ReflectionTestUtils.setField(p, "endpointOverride", ""); ReflectionTestUtils.setField(p, "publicBaseUrl", base);
        return p;
    }

    @Test
    void configurationNamesWhatIsMissingAndNeverGuesses() {
        MediaProperties empty = props("AUTO", "", "", "", "", "");
        assertEquals(List.of("CLOUDFLARE_R2_ACCOUNT_ID", "CLOUDFLARE_R2_ACCESS_KEY_ID", "CLOUDFLARE_R2_SECRET_ACCESS_KEY", "CLOUDFLARE_R2_BUCKET_NAME", "CLOUDFLARE_R2_PUBLIC_BASE_URL"), empty.missing());
        assertFalse(empty.r2Allowed());
        MediaProperties ok = props("AUTO", "acct123", "k", "s", "shopscale-media", "https://img.shopscale.com.ng/");
        assertTrue(ok.r2Allowed());
        assertEquals("https://acct123.r2.cloudflarestorage.com", ok.endpoint());
        assertEquals("https://img.shopscale.com.ng/merchants/x.jpg", ok.publicUrl("merchants/x.jpg"));
        assertFalse(props("DB", "acct123", "k", "s", "shopscale-media", "https://img.shopscale.com.ng").r2Allowed());
        assertFalse(props("AUTO", "acct123", "k", "s", "shopscale-media", "http://insecure.example").r2Allowed());
        assertFalse(props("AUTO", "acct123", "k", "s", "BAD BUCKET", "https://img.shopscale.com.ng").configured());
    }
}
