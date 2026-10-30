package org.mihomet.app.ui.screens.profiles

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Subscription QR generation: shares a remote profile by rendering its
 * subscription URL as a QR code another client can scan straight off the screen.
 *
 * The encode path ([encodeBitMatrix]) is pure JVM (zxing `core`, no Android
 * parts) and unit-testable, including a decode round-trip; only the render
 * step ([toBitmap]) touches `android.graphics`.
 */
object SubscriptionQr {

    /** Rendered side length in pixels: sharp on dense displays, scannable from arm's length. */
    const val QR_SIZE_PX = 600

    /** Module color (near-black) and background (pure white) for maximum camera contrast. */
    private const val MODULE_COLOR = 0xFF111318.toInt()
    private const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()

    /**
     * Encodes [content] into a square QR [BitMatrix] with error correction level
     * `M` (~15% damage recovery — a good density/robustness balance).
     *
     * Pure JVM. Throws (zxing `WriterException`) when the content does not fit
     * into a QR symbol at all; callers treat that as "no QR, show the URL".
     */
    fun encodeBitMatrix(content: String, size: Int = QR_SIZE_PX): BitMatrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        )

    /**
     * Renders [matrix] to a Bitmap: white background, dark modules — the
     * highest-contrast combination for camera scanning off a phone screen.
     */
    fun toBitmap(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) MODULE_COLOR else BACKGROUND_COLOR
            }
        }
        // RGB_565 is enough for two opaque colors and halves the memory footprint.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
