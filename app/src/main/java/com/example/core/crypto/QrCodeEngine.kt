package com.example.core.crypto

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates QR codes into Compose ImageBitmaps and decodes QR payloads from CameraX YUV planes or Bitmaps.
 */
object QrCodeEngine {

    fun generateQrBitmap(
        content: String,
        sizePx: Int = 512,
        darkColor: Int = Color.parseColor("#090F1A"),
        lightColor: Int = Color.parseColor("#FFFFFF"),
    ): ImageBitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix[x, y]) darkColor else lightColor
            }
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bitmap.asImageBitmap()
    }

    fun decodeFromYuv(
        yuvBytes: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        return try {
            val source = PlanarYUVLuminanceSource(
                yuvBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            MultiFormatReader().decode(binary, hints).text
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val intArray = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
            val binary = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binary).text
        } catch (_: Throwable) {
            null
        }
    }
}
