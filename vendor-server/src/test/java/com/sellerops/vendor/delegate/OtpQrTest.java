package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OtpQrTest {

    private static final String URI = "otpauth://totp/reviewnary:me%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
            + "&issuer=reviewnary&algorithm=SHA1&digits=6&period=30";

    @Test
    void theRowsAreASquareQrThatReadsBackAsTheUriItWasGiven() {
        List<String> rows = OtpQr.rows(URI);
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r).hasSize(rows.size()).matches("[01]+"));
        // Version 1 is 21 modules, and each version adds 4.
        assertThat((rows.size() - 21) % 4).isZero();
        assertThat(decode(rows.toArray(String[]::new))).isEqualTo(URI);
    }

    @Test
    void theTopLeftFinderIsDarkWhereThePageDrawsItsFirstRow() {
        // Orientation: a transposed or mirrored matrix still has three finders, but a reader would see another code.
        List<String> rows = OtpQr.rows(URI);
        assertThat(rows.get(0)).startsWith("1111111");
        assertThat(rows.get(1)).startsWith("1000001");
        assertThat(rows.get(rows.size() - 1)).startsWith("1111111");
        assertThat(rows.get(0)).endsWith("1111111");
    }

    /** Reads rows the way the join page draws them — four pixels a module, a four-module quiet zone — as a camera would. */
    public static String decode(String[] rows) {
        int scale = 4;
        int size = (rows.length + 8) * scale;
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                if (rows[y].charAt(x) != '1') {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[(4 * scale + y * scale + dy) * size + 4 * scale + x * scale + dx] = 0xFF191F28;
                    }
                }
            }
        }
        try {
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size,
                    pixels))), Map.of(DecodeHintType.CHARACTER_SET, "UTF-8")).getText();
        } catch (Exception e) {
            throw new AssertionError("the rows do not read as a QR code", e);
        }
    }
}
