package com.abhirha.shraa.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhirha.shraa.AssistantController
import com.abhirha.shraa.AssistantState
import com.abhirha.shraa.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rounded translucent card used for every section. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(ShPanel)
            .border(1.dp, ShBorder, shape)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = ShDim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = ShCyan)
}

fun stateLabel(s: AssistantState) = when (s) {
    AssistantState.READY -> "READY"
    AssistantState.STANDBY -> "STANDBY • SAY ‘SHRAA’"
    AssistantState.LISTENING -> "LISTENING"
    AssistantState.PROCESSING -> "PROCESSING"
    AssistantState.EXECUTING -> "EXECUTING"
    AssistantState.SPEAKING -> "SPEAKING"
}

fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
fun HistoryRow(item: HistoryItem) {
    val time = remember(item.time) {
        SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault()).format(Date(item.time))
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (item.success) ShGreen else ShRed))
            Spacer(Modifier.size(8.dp))
            Text(item.command, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
            Text(time, color = ShDim, fontSize = 11.sp)
        }
        Text("→ ${item.action}", color = ShCyan, fontSize = 12.sp, modifier = Modifier.padding(start = 15.dp, top = 2.dp), maxLines = 2)
        Text(item.result, color = ShDim, fontSize = 12.sp, modifier = Modifier.padding(start = 15.dp), maxLines = 2)
    }
}

/**
 * Returns `gate("mic" | "bg")`: explains the microphone permission, requests it,
 * then starts the requested feature. The explanation dialog is emitted by this composable.
 */
@Composable
fun rememberGate(): (String) -> Unit {
    val ctx = LocalContext.current
    val c = AssistantController
    var pending by remember { mutableStateOf<String?>(null) }
    var showRationale by remember { mutableStateOf(false) }

    fun run(kind: String?) {
        when (kind) {
            "bg" -> c.setBackground(true)
            "mic" -> c.startManualListening()
        }
    }

    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val kind = pending
        pending = null
        if (c.hasMic()) run(kind)
        else Toast.makeText(ctx, "Mic permission denied. Phone Settings se allow karo.", Toast.LENGTH_LONG).show()
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false; pending = null },
            title = { Text("Microphone permission") },
            text = {
                Text(
                    "SHRAA ko aapki awaaz sunne ke liye mic chahiye. Awaaz Android ki speech service " +
                        "se text banti hai; app audio save nahi karta. Background mode ON hone par " +
                        "notification me hamesha status dikhega aur wahin se band kar sakte ho."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRationale = false; launcher.launch(perms) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pending = null }) { Text("Not now") }
            }
        )
    }

    return { kind ->
        if (c.hasMic()) run(kind) else { pending = kind; showRationale = true }
    }
}
