package com.abhirha.shraa.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhirha.shraa.AssistantController
import com.abhirha.shraa.AssistantState
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

/** How long the last exchange stays after SHRAA finishes, before the greeting returns. */
private const val EXCHANGE_LINGER_MS = 600L

/**
 * Clean home: status, the SHRAA orb (tap it to speak), and one text area right under the status.
 * That area shows what she heard and answered while a command runs, and the moment the command
 * is finished it goes back to a greeting. No buttons here: navigation is the top and bottom bar.
 */
@Composable
fun HomeScreen(gate: (String) -> Unit) {
    val c = AssistantController
    val state by c.state.collectAsStateWithLifecycle()
    val online by c.online.collectAsStateWithLifecycle()
    val bg by c.backgroundOn.collectAsStateWithLifecycle()
    val bgStatus by c.bgStatus.collectAsStateWithLifecycle()
    val heard by c.heard.collectAsStateWithLifecycle()
    val reply by c.reply.collectAsStateWithLifecycle()
    val active = state == AssistantState.LISTENING || state == AssistantState.SPEAKING
    val idle = state == AssistantState.READY || state == AssistantState.STANDBY

    // The exchange is visible while she works, and for a moment after she finishes.
    var showExchange by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (!idle) {
            showExchange = true
        } else if (showExchange) {
            delay(EXCHANGE_LINGER_MS)
            showExchange = false
        }
    }
    val greeting = remember(idle && !showExchange) { pickGreeting() }

    // Mood: follows the state; turns sad after a failed command until the next one starts.
    val items by c.history.items.collectAsStateWithLifecycle()
    val openedAt = remember { System.currentTimeMillis() }
    var sad by remember { mutableStateOf(false) }
    val latest = items.firstOrNull()
    LaunchedEffect(latest) {
        if (latest != null && latest.time >= openedAt && !latest.success) sad = true
    }
    LaunchedEffect(state) {
        if (state == AssistantState.LISTENING || state == AssistantState.PROCESSING) sad = false
    }
    val mood = moodEmoji(state, online, sad)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) ShGreen else ShRed))
            Spacer(Modifier.size(8.dp))
            Text(
                if (online) "SYSTEM ONLINE" else "SYSTEM OFFLINE",
                color = if (online) ShGreen else ShRed, fontSize = 12.sp, letterSpacing = 2.sp
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShraaCore(
                state,
                Modifier
                    .fillMaxWidth(0.72f)
                    .clip(CircleShape)
                    .clickable {
                        if (state == AssistantState.READY || state == AssistantState.STANDBY) gate("mic")
                        else if (state == AssistantState.LISTENING) c.stopManualListening()
                    }
            )
            Text(stateLabel(state), color = ShCyan, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)

            // Right under the status: mood emoji on the left, the live exchange (or the greeting)
            // in the middle, and the mic button on the right.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoundSlot {
                    Crossfade(targetState = mood, label = "mood") { Text(it, fontSize = 28.sp) }
                }
                Column(
                    Modifier.weight(1f).heightIn(min = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    if (showExchange) {
                        if (heard.isNotBlank()) Text("“$heard”", color = ShDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                        if (reply.isNotBlank()) Text(reply, color = ShText, fontSize = 15.sp, textAlign = TextAlign.Center)
                    } else {
                        Text(
                            greeting, color = ShText, fontSize = 20.sp,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                        )
                    }
                }
                RoundSlot(
                    tint = if (state == AssistantState.LISTENING) ShGreen else ShCyan,
                    onClick = { if (idle) gate("mic") else if (state == AssistantState.LISTENING) c.stopManualListening() }
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (state == AssistantState.LISTENING) "Tap to stop" else "Tap to speak",
                        tint = if (state == AssistantState.LISTENING) ShGreen else ShCyan,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceWave(active = active)
            Text(
                if (bg) "Say ‘Shraa suno’, then your command  •  or tap the orb"
                else "Tap the orb to speak  •  turn Background ON to use ‘Shraa suno’",
                color = ShDim, fontSize = 12.sp, textAlign = TextAlign.Center
            )
            if (bg && bgStatus.isNotBlank()) {
                Text(bgStatus, color = ShRed, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A greeting that fits the time of day; a different one each time she returns to rest. */
private fun pickGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val options = when (hour) {
        in 5..11 -> listOf("Good morning, Sir", "Suprabhat, Sir", "Namaste, Sir", "Hello, Sir")
        in 12..16 -> listOf("Good afternoon, Sir", "Namaste, Sir", "Hello, Sir")
        in 17..20 -> listOf("Good evening, Sir", "Namaste, Sir", "Hello, Sir")
        else -> listOf("Hello, Sir", "Namaste, Sir", "Good night, Sir")
    }
    return options[Random.nextInt(options.size)]
}

/** A round 52dp slot used for the mood emoji and the mic button. */
@Composable
private fun RoundSlot(
    tint: androidx.compose.ui.graphics.Color = ShBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val base = Modifier.size(52.dp).clip(CircleShape).background(ShPanel).border(1.dp, tint, CircleShape)
    Box(if (onClick != null) base.clickable(onClick = onClick) else base, contentAlignment = Alignment.Center) {
        content()
    }
}

/** The face SHRAA is wearing right now. */
private fun moodEmoji(state: AssistantState, online: Boolean, sad: Boolean): String = when {
    sad -> "😕"
    state == AssistantState.LISTENING -> "🧐"
    state == AssistantState.PROCESSING -> "🤔"
    state == AssistantState.EXECUTING -> "😎"
    state == AssistantState.SPEAKING -> "😄"
    !online -> "😴"
    state == AssistantState.STANDBY -> "👀"
    else -> "😊"
}
