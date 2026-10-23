package com.abhirha.shraa.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.abhirha.shraa.apps.AppCatalog
import com.abhirha.shraa.apps.AppCategory
import com.abhirha.shraa.apps.ConnectedAppsStore
import com.abhirha.shraa.apps.InstalledApp
import com.abhirha.shraa.apps.MediaAccess
import com.abhirha.shraa.service.ShraaAccessibilityService
import java.util.Locale

/** Settings card: connect phone apps to SHRAA and grant the two access levels that control them. */
@Composable
fun ConnectedAppsCard(t: Int) {
    val ctx = LocalContext.current
    val store = remember { ConnectedAppsStore(ctx) }
    var version by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val apps = remember(open, t) { AppCatalog.load(ctx) }
    val allPkgs = remember(apps) { apps.map { it.pkg } }
    val v = version // re-read the store after every change
    val count = remember(v, apps) { store.connectedCount(allPkgs) }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("CONNECTED APPS")
        Text(
            "Phone ke apps SHRAA se connect karo: music, video, games, social, sab. Connected app ko hi SHRAA " +
                "awaaz se khol, chala aur control kar sakti hai.",
            color = ShDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "$count / ${apps.size} apps connected",
            color = if (count > 0) ShGreen else ShDim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp)
        )
        SwitchRow(
            title = "Saare apps connect",
            subtitle = "ON: phone ke sab apps connected. OFF: sirf wahi jo tum chuno.",
            checked = store.connectAll,
            onChange = { on -> if (on) store.connectEverything() else store.disconnectEverything(); version++ }
        )
        Button(onClick = { open = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Apps chuno") }

        Spacer(Modifier.height(10.dp))
        SectionTitle("ACCESS LEVELS")
        PermRow(
            "Media control (Notification access)",
            "Connected music/video app me play, pause, next, previous, 'kya chal raha hai'. Sirf media ka naam padhti hai.",
            MediaAccess.isEnabled(ctx)
        ) { safeStart(ctx, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        PermRow(
            "Phone control (Accessibility)",
            "Connected app ke andar back, home, scroll, button dabana, text likhna. Screen ka content store nahi hota.",
            ShraaAccessibilityService.isEnabled(ctx)
        ) { safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Text(
            "Android doosre app ki permissions khud allow karne nahi deta. Har app ko chalane ke liye upar ke dono access " +
                "ek baar de do, wo saare connected apps pe lagte hain. Games sirf khul sakte hain.",
            color = ShDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = ShBg) {
                AppsManager(apps, store, v, onChange = { version++ }, onClose = { open = false })
            }
        }
    }
}

private fun safeStart(ctx: Context, i: Intent) {
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun AppsManager(
    apps: List<InstalledApp>,
    store: ConnectedAppsStore,
    version: Int,
    onChange: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<AppCategory?>(null) }
    val allPkgs = remember(apps) { apps.map { it.pkg } }
    val shown = remember(apps, query, cat) {
        val q = query.trim().lowercase(Locale.ROOT)
        apps.filter { (cat == null || it.category == cat) && (q.isEmpty() || q in it.label.lowercase(Locale.ROOT)) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CONNECT APPS", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ShCyan, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search app") }, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("All ${apps.size}") })
            AppCategory.entries.forEach { c ->
                val n = apps.count { it.category == c }
                if (n > 0) FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text("${c.label} $n") })
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { store.setMany(shown.map { it.pkg }, true, allPkgs); onChange() }) { Text("Ye sab connect") }
            TextButton(onClick = { store.setMany(shown.map { it.pkg }, false, allPkgs); onChange() }) { Text("Ye sab hatao") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.pkg }) { app ->
                AppRow(app, store.isConnected(app.pkg), version) { on ->
                    store.set(app.pkg, on, allPkgs); onChange()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, connected: Boolean, @Suppress("UNUSED_PARAMETER") version: Int, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(app.pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
        else Spacer(Modifier.size(36.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ShText, maxLines = 1)
            Text(
                if (connected) "${app.category.label} • connected" else app.category.label,
                fontSize = 11.sp, color = if (connected) ShGreen else ShDim
            )
        }
        Switch(
            checked = connected, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = ShGreen, checkedThumbColor = ShBg)
        )
    }
}
