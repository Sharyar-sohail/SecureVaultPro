package com.securevault.pro.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes

/** Renders [value] as a QR on a white tile. Sensitive payloads must never reach this. */
@Composable
fun QrCodeImage(
    value: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val bitmap = remember(value) {
        runCatching {
            val dimension = 512
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, dimension, dimension)
            val pixels = IntArray(dimension * dimension) { index ->
                val x = index % dimension
                val y = index / dimension
                if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    if (bitmap == null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(VaultShapes.card)
                .background(VaultColor.qrLight),
            contentAlignment = Alignment.Center
        ) {
            Text("QR unavailable", style = MaterialTheme.typography.labelSmall, color = VaultColor.qrDark)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(VaultShapes.card)
            .background(VaultColor.qrLight)
            .padding(12.dp)
    )
}
