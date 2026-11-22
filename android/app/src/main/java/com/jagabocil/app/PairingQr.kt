package com.jagabocil.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.floor

private const val prefix = "jagabocil:pair:"
private val pairingCode = Regex("[0-9]{6}")

internal fun readPairingQr(text: String): String? =
    text.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf(pairingCode::matches)

@Composable
internal fun PairingQr(code: String, modifier: Modifier = Modifier) {
    val matrix = remember(code) { QRCodeWriter().encode(prefix + code, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.semantics { contentDescription = "QR untuk menyambungkan HP anak" }) {
        drawRect(Color.White)
        val module = floor(size.minDimension / matrix.width).coerceAtLeast(1f)
        val start = (size.minDimension - module * matrix.width) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(start + x * module, start + y * module), Size(module, module))
            }
        }
    }
}
