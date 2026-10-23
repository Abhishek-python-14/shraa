package com.abhirha.shraa.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhirha.shraa.AssistantController
import com.abhirha.shraa.usage.AppUsage
import com.abhirha.shraa.usage.DayUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private class Bar(val label: String, val top: String, val primary: Float, val secondary: Float = 0f)

private const val RECENT_SHOWN = 15

/** Profile: screen time, app usage, command stats and the recent command history. */
@Composable
fun ProfileScreen(tick: Int) {
    val ctx = LocalContext.current
    val usage = AssistantController.usage
    val granted = remember(tick) { usage.hasPermission() }
    var range by remember { mutableIntStateOf(1) } // 1 = today, 7 = last 7 days
    var confirmClear by remember { mutableStateOf(false) }

    val week by produceState<List<DayUsage>?>(null, tick) {
        value = withContext(Dispatchers.IO) { usage.lastDays(7) }
    }
    val apps by produceState<List<AppUsage>?>(null, tick, range) {
        value = withContext(Dispatchers.IO) { usage.topApps(range, 8) }
    }
    val history by AssistantController.history.items.collectAsStateWithLifecycle()
    val dayFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val openUsageAccess = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle("PROFILE")

        // ---- Screen time ----
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("SCREEN TIME • LAST 7 DAYS")
            Spacer(Modifier.height(8.dp))
            val days = week
            when {
                !granted -> NeedUsageAccess(openUsageAccess)
                days == null -> Text("Loading…", color = ShDim, fontSize = 13.sp)
                else -> {
                    val today = days.lastOrNull()?.millis ?: 0L
                    val avg = if (days.isEmpty()) 0L else days.sumOf { it.millis } / days.size
                    Text(fmtDuration(today), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ShCyan)
                    Text("aaj  •  7 din ka average ${fmtDuration(avg)}", color = ShDim, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    BarChart(
                        days.map { Bar(dayFmt.format(Date(it.dayStart)), fmtDuration(it.millis), it.millis.toFloat()) },
                        ShCyan
                    )
                }
            }
        }

        // ---- App-wise usage ----
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("APP-WISE USAGE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = range == 1, onClick = { range = 1 }, label = { Text("Aaj") })
                FilterChip(selected = range == 7, onClick = { range = 7 }, label = { Text("7 din") })
            }
            Spacer(Modifier.height(8.dp))
            val list = apps
            when {
                !granted -> NeedUsageAccess(openUsageAccess)
                list == null -> Text("Loading…", color = ShDim, fontSize = 13.sp)
                list.isEmpty() -> Text("Abhi koi app usage data nahi hai.", color = ShDim, fontSize = 13.sp)
                else -> {
                    val max = list.first().millis.toFloat()
                    list.forEach { app -> UsageRow(app.label, fmtDuration(app.millis), app.millis / max, ShCyan) }
                }
            }
        }

        // ---- Command statistics ----
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("COMMANDS • LAST 7 DAYS")
            Spacer(Modifier.height(8.dp))
            val startToday = remember(tick, history.size) {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            }
            val buckets = (6 downTo 0).map { i ->
                val c = (startToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
                val from = c.timeInMillis
                val to = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                val inDay = history.filter { it.time in from until to }
                Triple(from, inDay.count { it.success }, inDay.count { !it.success })
            }
            val recent = history.filter { it.time >= buckets.first().first }
            if (recent.isEmpty()) {
                Text("Pichhle 7 din me koi command nahi.", color = ShDim, fontSize = 13.sp)
            } else {
                val ok = recent.count { it.success }
                Text(
                    "${recent.size} commands  •  ${ok * 100 / recent.size}% successful",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                BarChart(
                    buckets.map { (from, good, bad) ->
                        Bar(dayFmt.format(Date(from)), (good + bad).takeIf { it > 0 }?.toString() ?: "", good.toFloat(), bad.toFloat())
                    },
                    ShGreen
                )
                Text("Green = ho gaye, red = fail", color = ShDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                SectionTitle("MOST USED")
                val top = recent.groupBy { actionKind(it.action) }.entries
                    .sortedByDescending { it.value.size }.take(4)
                val topMax = top.first().value.size.toFloat()
                top.forEach { (kind, items) -> UsageRow(kind, "${items.size}", items.size / topMax, ShGreen) }
            }
        }

        // ---- Recent history ----
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SectionTitle("RECENT COMMANDS") }
                if (history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear", color = ShRed) }
                }
            }
            if (history.isEmpty()) {
                Text("Abhi koi command history nahi hai. History sirf is phone me save hoti hai.", color = ShDim, fontSize = 13.sp)
            } else {
                Spacer(Modifier.height(6.dp))
                history.take(RECENT_SHOWN).forEachIndexed { i, item ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    HistoryRow(item)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Saari command history is phone se delete ho jayegi.") },
            confirmButton = {
                TextButton(onClick = { AssistantController.history.clear(); confirmClear = false }) {
                    Text("Delete", color = ShRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

/** "OPEN_APP youtube" -> "OPEN_APP", "Choice: Rahul" -> "Choice". */
private fun actionKind(action: String): String =
    action.substringBefore(' ').substringBefore(':').ifBlank { "Other" }

@Composable
private fun NeedUsageAccess(onGrant: () -> Unit) {
    Text(
        "Ye dikhane ke liye Android ka Usage Access permission chahiye. Data sirf aapke phone pe padha jata hai.",
        color = ShDim, fontSize = 13.sp
    )
    TextButton(onClick = onGrant) { Text("Grant Usage Access") }
}

@Composable
private fun UsageRow(label: String, value: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, color = ShDim, fontSize = 12.sp)
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 4.dp),
        color = color,
        trackColor = Color(0x1AFFFFFF)
    )
}

@Composable
private fun BarChart(bars: List<Bar>, primary: Color, secondary: Color = ShRed) {
    val maxV = (bars.maxOfOrNull { it.primary + it.secondary } ?: 0f).coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().height(150.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEach { b ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(b.top, fontSize = 9.sp, color = ShDim, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                val topShape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                if (b.secondary > 0f) {
                    Spacer(
                        Modifier.padding(horizontal = 6.dp).fillMaxWidth()
                            .height((96f * b.secondary / maxV).dp.coerceAtLeast(2.dp))
                            .background(secondary, topShape)
                    )
                }
                Spacer(
                    Modifier.padding(horizontal = 6.dp).fillMaxWidth()
                        .height((96f * b.primary / maxV).dp.coerceAtLeast(2.dp))
                        .background(primary, if (b.secondary > 0f) RoundedCornerShape(0.dp) else topShape)
                )
                Spacer(Modifier.height(3.dp))
                Text(b.label, fontSize = 10.sp, color = ShText, maxLines = 1)
            }
        }
    }
}
