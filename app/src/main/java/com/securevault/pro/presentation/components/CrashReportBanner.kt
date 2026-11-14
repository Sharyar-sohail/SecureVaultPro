package com.securevault.pro.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType

/**
 * Shows the fault the previous process recorded, on every screen and until the owner
 * says they have seen it. The trace used to be handed over once through a dialog that a
 * repeating crash destroyed before it could be read, which left a crash loop undiagnosable
 * from the phone alone.
 */
@Composable
fun CrashReportBanner(modifier: Modifier = Modifier) {
    val pending by CrashLogger.pending.collectAsStateWithLifecycle()
    val report = pending ?: return
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
            .clip(VaultShapes.button)
            .background(VaultColor.elevated.copy(alpha = 0.97f))
            .border(1.dp, VaultColor.warning.copy(alpha = 0.55f), VaultShapes.button)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = Glyph.errorOutline,
                contentDescription = null,
                tint = VaultColor.warning,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Something went wrong last time",
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Copy the report and send it over, or open it later from " +
                "${CrashLogger.REPORT_LOCATION} in the Files app. Automatic biometric unlock " +
                "stays paused until you dismiss this, so the same fault cannot hide the report " +
                "again.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 132.dp)
                .clip(VaultShapes.input)
                .background(VaultColor.overlay.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = if (report.second.isBlank()) {
                    "${report.first}\n\nSaved to: ${CrashLogger.REPORT_LOCATION}"
                } else {
                    "${report.first}\n\nSaved to: ${CrashLogger.REPORT_LOCATION}\n" +
                        "Also at: ${report.second}"
                },
                style = VaultType.micro().copy(fontFamily = FontFamily.Monospace),
                color = VaultColor.textStrong,
                textAlign = TextAlign.Start
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { copyReport(context, report.first) }) {
                Text(text = "Copy", color = VaultColor.warning, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { CrashLogger.acknowledge(context) }) {
                Text(text = "Dismiss", color = VaultColor.textFaint, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun copyReport(context: Context, trace: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("crash report", trace))
        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
    }
}
