package com.arflix.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeImage(
    data: String,
    sizePx: Int,
    modifier: Modifier = Modifier,
    foreground: Int = android.graphics.Color.BLACK,
    background: Int = android.graphics.Color.WHITE,
) {
    if (sizePx <= 0 || data.isBlank()) return

    val bitmap = remember(data, sizePx, foreground, background) {
        try {
            val writer = QRCodeWriter()
            val matrix = writer.encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) foreground else background
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            android.util.Log.e("QrCodeImage", "Failed to generate QR code bitmap", e)
            null
        }
    } ?: return

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.component_qr_code),
        modifier = modifier,
    )
}
