package com.abhirha.shraa.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhirha.shraa.AssistantController
import com.abhirha.shraa.ai.AiProvider
import kotlinx.coroutines.launch

/** Everything configurable lives here: background assistant, AI keys, voice and permissions. */
@Composable
fun SettingsScreen(tick: Int, gate: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = AssistantController
    var refresh by remember { mutableIntStateOf(0) }
    val t = tick + refresh

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle("SETTINGS")

        BackgroundCard(gate)
        ConnectedAppsCard(t)
        AiCard(t) { refresh++ }
        VoiceCard(t)

        // ---- Permissions ----
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("PERMISSIONS & SETUP")
            Spacer(Modifier.height(4.dp))
            PermRow(
                "Microphone", "Aapki awaaz sunne ke liye (wake word aur commands).",
                granted(Manifest.permission.RECORD_AUDIO)
            ) { launcher.launch(Manifest.permission.RECORD_AUDIO) }
            if (Build.VERSION.SDK_INT >= 33) {
                PermRow(
                    "Notifications", "Background mode me 'SHRAA is active' status aur Stop button dikhane ke liye.",
                    granted(Manifest.permission.POST_NOTIFICATIONS)
                ) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            PermRow(
                "Contacts", "'Rahul ko call karo' jaise commands me naam se number dhundhne ke liye.",
                granted(Manifest.permission.READ_CONTACTS)
            ) { launcher.launch(Manifest.permission.READ_CONTACTS) }
            PermRow(
                "Phone calls", "Call seedha lagane ke liye. Iske bina dialer khulta hai.",
                granted(Manifest.permission.CALL_PHONE)
            ) { launcher.launch(Manifest.permission.CALL_PHONE) }
            PermRow(
                "SMS", "'Rahul ko sms bhejo' ko seedha send karne ke liye. Iske bina message taiyaar hota hai, send aap dabate ho.",
                granted(Manifest.permission.SEND_SMS)
            ) { launcher.launch(Manifest.permission.SEND_SMS) }
            PermRow(
                "Calendar", "'Good morning' brief me aane wale events batane ke liye (optional).",
                granted(Manifest.permission.READ_CALENDAR)
            ) { launcher.launch(Manifest.permission.READ_CALENDAR) }
            PermRow(
                "Location (approx.)", "'Good morning' brief me weather ke liye. Sirf last known location (optional).",
                granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
            PermRow(
                "Usage access", "Screen time dikhane ke liye (sirf phone pe padha jata hai).",
                c.usage.hasPermission()
            ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            if (Build.VERSION.SDK_INT >= 23) {
                PermRow(
                    "Display over other apps", "Wake pop-up dikhane aur Background me apps kholne ke liye zaroori.",
                    Settings.canDrawOverlays(ctx)
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                PermRow(
                    "Battery: unrestricted", "Android Background listening band na kare (optional).",
                    pm.isIgnoringBatteryOptimizations(ctx.packageName)
                ) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        Text(CREDIT, color = ShDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Background assistant
// ---------------------------------------------------------------------------------------------

@Composable
private fun BackgroundCard(gate: (String) -> Unit) {
    val c = AssistantController
    val bg by c.backgroundOn.collectAsStateWithLifecycle()
    var looseWake by remember { mutableStateOf(c.settings.looseWake) }
    var followUp by remember { mutableStateOf(c.settings.followUp) }
    var quiet by remember { mutableStateOf(c.settings.quietListening) }
    var lockPopup by remember { mutableStateOf(c.settings.lockScreenPopup) }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("BACKGROUND ASSISTANT")
        SwitchRow(
            title = if (bg) "ON • standby, wakes only on ‘Shraa suno’" else "OFF",
            subtitle = "Google ki tarah: ON karne ke baad SHRAA kuch nahi karti jab tak tum ‘Shraa suno’ na bolo. Phir popup aata hai aur wo command sunti hai. Notification se kabhi bhi band kar sakte ho.",
            checked = bg,
            onChange = { on -> if (on) gate("bg") else c.setBackground(false) },
            titleColor = if (bg) ShGreen else ShText
        )
        SwitchRow(
            title = "Lock screen popup",
            subtitle = "ON: phone lock ho tab bhi ‘Shraa suno’ bolne par SHRAA ka popup aata hai aur screen on hoti hai. OFF: lock me popup nahi dikhta, screen on nahi hoti.",
            checked = lockPopup,
            onChange = { lockPopup = it; c.settings.lockScreenPopup = it }
        )
        SwitchRow(
            title = "Quiet listening (bip band)",
            subtitle = "ON: standby me har kuch second ka bip nahi aata. Bip sirf ‘Shraa suno’ ke baad aata hai. Kuch notification sound miss ho to OFF karo.",
            checked = quiet,
            onChange = { quiet = it; c.settings.quietListening = it }
        )
        SwitchRow(
            title = "Sensitive wake word",
            subtitle = "ON: 'Sara suno' jaise galat suney naam par bhi jage. Galti se active ho to OFF rakho.",
            checked = looseWake,
            onChange = { looseWake = it; c.settings.looseWake = it }
        )
        SwitchRow(
            title = "Follow-up mode",
            subtitle = "ON: kaam ke baad kuch second aur sunti hai, bina 'Shraa suno' bole agla command. OFF: ek wake = ek command.",
            checked = followUp,
            onChange = { followUp = it; c.settings.followUp = it }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Online AI: Gemini / ZenMux
// ---------------------------------------------------------------------------------------------

@Composable
private fun AiCard(t: Int, onChanged: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("ONLINE AI")
        Text(
            "SHRAA Google Gemini use karti hai. Apni Gemini key daalo. Bina key ya internet ke SHRAA offline basic mode me chalti hai.",
            color = ShDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )
        AiProvider.entries.forEach { p ->
            Text(
                p.label.uppercase() + " • " + p.defaultModel,
                color = ShCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp)
            )
            ProviderEditor(p, t, onChanged)
        }
    }
}

@Composable
private fun ProviderEditor(provider: AiProvider, t: Int, onChanged: () -> Unit) {
    val c = AssistantController
    var keyInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var local by remember { mutableIntStateOf(0) }
    var showKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val savedKey = remember(t, local) { c.keyStore.get(provider) }

    Text(
        if (savedKey != null) "Key saved: ${savedKey.take(6)}…${savedKey.takeLast(4)} (${savedKey.length} chars)"
        else "Key nahi hai. ${provider.keySource} se banao.",
        color = if (savedKey != null) ShGreen else ShDim, fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text(provider.keyHint) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        // Save also connects: the key is checked right away against that provider.
        Button(
            enabled = !connecting && keyInput.isNotBlank(),
            onClick = {
                val cleaned = keyInput.filter { it.code in 33..126 && it != '"' && it != '\'' }
                if (cleaned.isNotBlank()) {
                    c.keyStore.save(provider, cleaned)
                    keyInput = ""
                    local++
                    onChanged()
                    connecting = true
                    result = "Connecting…"
                    scope.launch { result = c.testAi(provider); connecting = false }
                }
            }
        ) { Text("Save & Connect") }
        if (savedKey != null) {
            OutlinedButton(onClick = { c.keyStore.clear(provider); result = ""; local++; onChanged() }) { Text("Remove") }
        }
    }
    if (result.isNotBlank()) {
        Text(
            if (result.startsWith("OK")) "Connected ✓ ${provider.label}" else result,
            fontSize = 12.sp,
            color = if (result.startsWith("OK") || connecting) ShGreen else ShRed,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Voice
// ---------------------------------------------------------------------------------------------

@Composable
private fun VoiceCard(t: Int) {
    val c = AssistantController
    var rate by remember { mutableFloatStateOf(c.settings.ttsRate) }
    var pitch by remember { mutableFloatStateOf(c.settings.ttsPitch) }
    var voiceName by remember { mutableStateOf(c.settings.ttsVoice) }
    var lang by remember { mutableStateOf(c.settings.speechLang) }
    var voiceMenu by remember { mutableStateOf(false) }
    val voices = remember(t) { c.tts.availableVoices() }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("VOICE")
        Spacer(Modifier.height(8.dp))
        Text("Listening language", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = lang == "hi-IN",
                onClick = { lang = "hi-IN"; c.settings.speechLang = "hi-IN" },
                label = { Text("हिंदी / Hinglish") }
            )
            FilterChip(
                selected = lang == "en-IN",
                onClick = { lang = "en-IN"; c.settings.speechLang = "en-IN" },
                label = { Text("English") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("SHRAA voice", fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { voiceMenu = true }) { Text(voiceName ?: "Auto (default)") }
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                DropdownMenuItem(text = { Text("Auto (default)") }, onClick = {
                    voiceName = null; c.settings.ttsVoice = null; voiceMenu = false
                })
                voices.forEach { v ->
                    DropdownMenuItem(text = { Text(v.name) }, onClick = {
                        voiceName = v.name; c.settings.ttsVoice = v.name; voiceMenu = false
                    })
                }
            }
        }
        Text("Speed: ${"%.1f".format(rate)}x", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Slider(value = rate, onValueChange = { rate = it; c.settings.ttsRate = it }, valueRange = 0.5f..2f)
        Text("Pitch: ${"%.1f".format(pitch)}", fontSize = 13.sp)
        Slider(value = pitch, onValueChange = { pitch = it; c.settings.ttsPitch = it }, valueRange = 0.5f..2f)
        OutlinedButton(onClick = { c.testVoice() }) { Text("Test voice") }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared rows
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    titleColor: androidx.compose.ui.graphics.Color = ShText
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = titleColor)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = ShDim)
        }
        Spacer(Modifier.size(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = ShGreen, checkedThumbColor = ShBg)
        )
    }
}

@Composable
internal fun PermRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(why, color = ShDim, fontSize = 12.sp)
        }
        Spacer(Modifier.size(8.dp))
        if (granted) Text("ON", color = ShGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        else TextButton(onClick = onGrant) { Text("Grant") }
    }
}
