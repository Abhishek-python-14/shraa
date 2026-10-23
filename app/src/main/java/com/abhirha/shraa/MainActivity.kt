package com.abhirha.shraa

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhirha.shraa.ui.CREDIT
import com.abhirha.shraa.ui.HomeScreen
import com.abhirha.shraa.ui.ProfileScreen
import com.abhirha.shraa.ui.SettingsScreen
import com.abhirha.shraa.ui.ShBg
import com.abhirha.shraa.ui.ShBorder
import com.abhirha.shraa.ui.ShCyan
import com.abhirha.shraa.ui.ShDim
import com.abhirha.shraa.ui.ShGreen
import com.abhirha.shraa.ui.ShText
import com.abhirha.shraa.ui.ShraaTheme
import com.abhirha.shraa.ui.rememberGate

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent { ShraaTheme { ShraaRoot(tick.intValue) } }
    }

    override fun onStart() {
        super.onStart()
        AssistantController.appVisible = true
    }

    override fun onStop() {
        AssistantController.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++                      // refresh permission + screen-time state
        AssistantController.refreshOnline()
        AssistantController.syncBackground() // restart the service if Background was left ON
    }
}

private const val HOME = 0
private const val PROFILE = 1
private const val SETTINGS = 2

@Composable
private fun ShraaRoot(tick: Int) {
    var screen by rememberSaveable { mutableIntStateOf(HOME) }
    val bgOn by AssistantController.backgroundOn.collectAsStateWithLifecycle()
    val gate = rememberGate()

    BackHandler(enabled = screen != HOME) { screen = HOME }

    Scaffold(
        containerColor = ShBg,
        topBar = {
            TopBar(
                inSettings = screen == SETTINGS,
                onSettings = { screen = SETTINGS },
                onBack = { screen = HOME }
            )
        },
        bottomBar = {
            BottomBar(
                screen = screen,
                bgOn = bgOn,
                onHome = { screen = HOME },
                onProfile = { screen = PROFILE },
                onBackground = { if (bgOn) AssistantController.setBackground(false) else gate("bg") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (screen) {
                HOME -> HomeScreen(gate)
                PROFILE -> ProfileScreen(tick)
                else -> SettingsScreen(tick, gate)
            }
        }
    }
}

/** Full name on the left, one Settings button on the right (a back arrow inside Settings). */
@Composable
private fun TopBar(inSettings: Boolean, onSettings: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = if (inSettings) 6.dp else 20.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inSettings) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ShText)
            }
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("SHRAA", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = ShCyan)
            Text(CREDIT, fontSize = 12.sp, color = ShDim, letterSpacing = 2.sp)
        }
        if (!inSettings) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ShText)
            }
        }
    }
}

/** Exactly three buttons: Home, Profile, Background assistant ON/OFF. */
@Composable
private fun BottomBar(
    screen: Int,
    bgOn: Boolean,
    onHome: () -> Unit,
    onProfile: () -> Unit,
    onBackground: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF070C14))
            .border(1.dp, ShBorder.copy(alpha = 0.15f))
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BarItem(Icons.Filled.Home, "Home", screen == HOME, ShCyan, onHome)
        BarItem(Icons.Filled.Person, "Profile", screen == PROFILE, ShCyan, onProfile)
        BarItem(
            if (bgOn) Icons.Filled.Mic else Icons.Filled.MicOff,
            if (bgOn) "Background ON" else "Background OFF",
            bgOn, ShGreen, onBackground
        )
    }
}

@Composable
private fun RowScope.BarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    val color = if (selected) tint else ShDim
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .weight(1f)
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}
