package com.sellerops.vendor.delegate;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The authenticator QR for a staged invite, as rows of modules — contract §2. The join page runs under
 * {@code default-src 'self'} (no image from elsewhere, no {@code data:} image), so it cannot ask a QR service and does
 * not carry an encoder; it draws these rows on a canvas. The rows hold exactly what {@link Delegates.Staged} already
 * hands the page in {@code otpauthUri}, nothing more.
 */
public final class OtpQr {

    private OtpQr() {
    }

    /** Top row first; each row a string of {@code '1'} (dark) and {@code '0'} (light), without the quiet zone. */
    public static List<String> rows(String text) {
        ByteMatrix matrix;
        try {
            // M: the level authenticator setup screens commonly use; the URI fits comfortably.
            matrix = Encoder.encode(text, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"))
                    .getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("otpauth URI does not fit a QR code", e);
        }
        List<String> rows = new ArrayList<>(matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            StringBuilder row = new StringBuilder(matrix.getWidth());
            for (int x = 0; x < matrix.getWidth(); x++) {
                row.append(matrix.get(x, y) == 1 ? '1' : '0');
            }
            rows.add(row.toString());
        }
        return rows;
    }
}
