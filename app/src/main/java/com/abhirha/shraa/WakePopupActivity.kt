package com.abhirha.shraa

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhirha.shraa.ui.ShBorder
import com.abhirha.shraa.ui.ShCyan
import com.abhirha.shraa.ui.ShDim
import com.abhirha.shraa.ui.ShText
import com.abhirha.shraa.ui.ShraaCore
import com.abhirha.shraa.ui.ShraaTheme
import com.abhirha.shraa.ui.VoiceWave
import com.abhirha.shraa.ui.stateLabel
import kotlinx.coroutines.delay

/**
 * Wake pop-up: a small SHRAA card at the bottom of the screen, shown over any app (and the lock
 * screen) when "Shraa" is heard. It closes by itself shortly after she is back at rest, and
 * closing it never stops the assistant.
 */
class WakePopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AssistantController.settings.lockScreenPopup) {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
        setContent { ShraaTheme { PopupCard(onClose = { finish() }) } }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun PopupCard(onClose: () -> Unit) {
    val c = AssistantController
    val state by c.state.collectAsStateWithLifecycle()
    val heard by c.heard.collectAsStateWithLifecycle()
    val reply by c.reply.collectAsStateWithLifecycle()
    var wasAwake by remember { mutableStateOf(false) }

    // Close ~1.4s after SHRAA returns to rest, but only once she was actually awake.
    LaunchedEffect(state) {
        if (state != AssistantState.STANDBY && state != AssistantState.READY) {
            wasAwake = true
        } else if (wasAwake) {
            delay(1400)
            onClose()
        }
    }
    // Safety net: never hang around if the wake never really started.
    LaunchedEffect(Unit) {
        delay(15_000)
        if (!wasAwake) onClose()
    }

    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(14.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xF20B1220))
                .border(1.dp, ShBorder, shape)
                .clickable(enabled = false) {}
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SHRAA", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp,
                    color = ShCyan, modifier = Modifier.weight(1f)
                )
                Text(stateLabel(state), color = ShDim, fontSize = 11.sp, letterSpacing = 2.sp)
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = ShText)
                }
            }
            Box(Modifier.height(130.dp), contentAlignment = Alignment.Center) {
                ShraaCore(state, Modifier.size(130.dp))
            }
            VoiceWave(active = state == AssistantState.LISTENING || state == AssistantState.SPEAKING)
            if (heard.isNotBlank()) Text("“$heard”", color = ShDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            Text(
                reply.ifBlank { "Boliye, main sun rahi hu…" },
                color = ShText, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}
