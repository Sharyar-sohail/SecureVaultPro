package com.securevault.pro.presentation.screens.diagnostics

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.diagnostics.CrashLogger

/**
 * The screen that appears instead of the app when the app is the thing that keeps dying.
 *
 * A launch that died while still coming up, or two that never said goodbye, means the normal
 * UI cannot be trusted to report on itself: it would crash before the banner could be read. So
 * this renders from the stock Material theme alone — no palette lookup, no chosen font, no
 * backdrop blur, no vault data, no navigation graph, and no view model — which makes it the
 * one surface that can survive whatever took the rest of the process down. It exists to show
 * the report and to get the phone back into the app.
 */
@Composable
fun RescueScreen(
    onOpenAnyway: () -> Unit,
    onResetLook: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pending by CrashLogger.pending.collectAsState()
    val report = remember(pending) {
        pending?.first ?: "No stack trace was recorded. The previous runs ended without " +
            "closing cleanly, which points at the graphics pipeline or the system closing " +
            "the app rather than at Kotlin code."
    }
    val steps = remember { CrashLogger.lastSteps() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "RECOVERY MODE",
            color = Color(0xFF7FD8BE),
            fontSize = 11.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "The vault could not finish opening.",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
        Text(
            text = "Nothing here deletes or changes your data. Try the first option — " +
                "it is the one that can fix a fault that only appears on one theme or font. " +
                "This opens on the first failed launch now, and a copy of the report is " +
                "written to ${CrashLogger.REPORT_LOCATION}, which the Files app can open.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB9C3CC),
            lineHeight = 21.sp
        )

        Text(
            text = "Build and device",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF8D97A1),
            fontWeight = FontWeight.SemiBold
        )
        ReportBlock(
            "Secure Wallet Pro · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "Last reached: ${CrashLogger.lastStage}"
        )

        Text(
            text = "What the vault reported",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF8D97A1),
            fontWeight = FontWeight.SemiBold
        )
        ReportBlock(report)

        if (steps.isNotBlank()) {
            Text(
                text = "Last steps",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF8D97A1),
                fontWeight = FontWeight.SemiBold
            )
            ReportBlock(steps)
        }

        Spacer(Modifier.height(2.dp))
        Button(
            onClick = { onResetLook(); CrashLogger.leaveRescue() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E6F5C),
                contentColor = Color.White
            )
        ) {
            Text("Reset the look and open", modifier = Modifier.padding(vertical = 6.dp))
        }
        OutlinedButton(
            onClick = { CrashLogger.leaveRescue(); onOpenAnyway() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open anyway", modifier = Modifier.padding(vertical = 6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString("$report\n\nLast steps:\n$steps")) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy report")
            }
            OutlinedButton(
                onClick = { CrashLogger.acknowledge(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear report")
            }
        }
        Text(
            text = "Copy the report before clearing it. It is the only description of what " +
                "happened on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF727C86),
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun ReportBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF171D23))
            .padding(14.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFFD6DEE6),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}
