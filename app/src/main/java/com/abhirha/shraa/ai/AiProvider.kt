package com.abhirha.shraa.ai

/**
 * The online AI behind SHRAA: Google Gemini. It is used only when its API key is saved
 * (Settings > Online AI). Without a key or internet, the offline basic mode still works.
 * Kept as an enum so the settings/key code stays generic.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val defaultModel: String,
    val keyHint: String,
    val keySource: String,
    val keyPrefName: String,
    val baseUrl: String,
    val fallbackModels: List<String>
) {
    GEMINI(
        id = "gemini",
        label = "Gemini",
        defaultModel = "gemini-flash-latest",
        keyHint = "AIza… or AQ.…",
        keySource = "aistudio.google.com",
        keyPrefName = "gemini_api_key",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        fallbackModels = listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite")
    )
}
