package com.abhirha.shraa.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

const val CREDIT = "by Ꭺʙʜɪʀᴀʜ !!"

val ShBg = Color(0xFF04070C)
val ShPanel = Color(0x14FFFFFF)
val ShBorder = Color(0x3322D3EE)
val ShCyan = Color(0xFF22D3EE)
val ShBlue = Color(0xFF3B82F6)
val ShGreen = Color(0xFF34D399)
val ShRed = Color(0xFFF87171)
val ShText = Color(0xFFE6F1FF)
val ShDim = Color(0xFF7B8CA3)

private val ShSurface = Color(0xFF0B1220)

@Composable
fun ShraaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ShCyan,
            onPrimary = ShBg,
            secondary = ShBlue,
            background = ShBg,
            surface = ShSurface,
            onBackground = ShText,
            onSurface = ShText,
            surfaceVariant = ShSurface,
            onSurfaceVariant = ShDim,
            outline = ShBorder
        ),
        content = content
    )
}
