package org.mihomet.app

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.ui.screens.profiles.SubscriptionQr

/**
 * JVM tests for subscription QR generation ([SubscriptionQr.encodeBitMatrix],
 * zxing core): geometry, dark-module sanity, a decode round-trip (the QR must
 * scan back to the exact subscription URL) and graceful failure on oversized
 * payloads.
 */
class SubscriptionQrTest {

    private fun toLuminancePixels(matrix: BitMatrix): IntArray {
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // ARGB: dark module -> black, light -> white (matches SubscriptionQr.toBitmap).
                pixels[y * matrix.width + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return pixels
    }

    private fun darkModules(matrix: BitMatrix): Int {
        var dark = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) dark++
            }
        }
        return dark
    }

    @Test
    fun `bitmatrix is square at the requested size`() {
        val matrix = SubscriptionQr.encodeBitMatrix("https://sub.example.com/s/abc123", 600)

        assertEquals(600, matrix.width)
        assertEquals(600, matrix.height)
        assertEquals(SubscriptionQr.QR_SIZE_PX, SubscriptionQr.encodeBitMatrix("https://x.io/q").width)
    }

    @Test
    fun `bitmatrix contains a sane amount of dark modules`() {
        val matrix = SubscriptionQr.encodeBitMatrix("https://sub.example.com/s/abc123", 600)
        val dark = darkModules(matrix)
        val total = matrix.width * matrix.height

        assertTrue("QR must not be blank (dark=$dark)", dark > 0)
        assertTrue("density implausibly low ($dark/$total)", dark > total / 10)
        assertTrue("density implausibly high ($dark/$total)", dark < total * 9 / 10)
    }

    @Test
    fun `roundtrip decodes back to the exact subscription url`() {
        val url = "https://sub.example.com/s/abc123?token=xyz789&u=42"
        val matrix = SubscriptionQr.encodeBitMatrix(url, 400)

        val source = RGBLuminanceSource(matrix.width, matrix.height, toLuminancePixels(matrix))
        val result = QRCodeReader().decode(
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true)
        )

        assertEquals(url, result.text)
    }

    @Test
    fun `oversized payload fails instead of rendering garbage`() {
        val tooLong = "https://sub.example.com/s/" + "a".repeat(5000)

        assertThrows(Exception::class.java) {
            SubscriptionQr.encodeBitMatrix(tooLong, 600)
        }
    }
}
