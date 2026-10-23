# SHRAA

**SHRAA** is a voice-first personal assistant for Android, built for private use on one phone.
Say **"Shraa suno"**, and she wakes up, listens to one command (Hindi, English or Hinglish), tells you what she is doing, does it, and goes back to rest until the next wake phrase.

| | |
|---|---|
| App name / package | `shraa` / `com.abhirha.shraa` |
| Language / UI | Kotlin, Jetpack Compose |
| SDK | minSdk 21, compile/target SDK 34 |
| Build | Gradle 9.6.1, Android Gradle Plugin 9.4.0, Kotlin 2.4.10, JDK 17+ |
| Online AI | Google Gemini API (your own key) |
| Author | Abhirah |

> Personal-use project. Do not publish the app or its keys publicly. See [Security](#security-before-you-push-to-github).

## Features

**Voice and wake**
- Wake phrase **"Shraa suno"** (like "Hey Google"), optionally followed by the command in one go. "Shraa" alone or other speech is ignored.
- Wake pop-up card over any app and the lock screen; screen turns on (Settings switch for the lock screen).
- One command per wake. Optional Follow-up mode keeps listening a few seconds for the next command.
- SHRAA first says what she is doing ("Haan, main YouTube khol rahi hu"), and only after she finishes speaking does the task run.
- Female voice, Hindi/Hinglish/English; voice, speed and pitch adjustable.

**What she can do**
- Open any installed app by name, even misspelled or in Hindi script (closest installed app is picked; suggestions come only from installed apps).
- Play a song/video or search inside any installed app (Spotify, YouTube Music, JioSaavn, Gaana, Amazon, ...).
- Call, SMS, WhatsApp, Instagram, Facebook, Telegram: **send** a message, or only **type** it ("open WhatsApp and type ..."). Several contacts with the same name: she reads the options and asks which one.
- Install any app from the Play Store by voice.
- Alarms and reminders ("kal subah 6 baje utha dena"), calendar events, "Good morning" brief (time, next alarm, calendar, weather).
- Volume, torch, settings pages, media keys, back/home/scroll/tap/type inside connected apps.
- Conversation context for 3 minutes ("YouTube kholo", then "ab Arijit Singh ka song chalao").
- Offline basic mode: alarms, volume, torch, open app, call, settings, media keys, time and the morning brief work without internet.
- Profile tab: 7-day screen time, app-wise usage, command statistics and local command history.
- **Connected Apps**: choose which installed apps SHRAA may control.

## Quick start

1. Open this folder in Android Studio or Code on the Go and let Gradle sync.
2. Run on a **real phone** (Android 5.0+, 8.0+ recommended). Emulators are poor at speech recognition.
3. Get a free Gemini key at [aistudio.google.com](https://aistudio.google.com) and paste it in **Settings > Online AI > Save & Connect**.
4. Grant permissions in **Settings > Permissions & Setup** (Microphone first; the rest are optional per feature).
5. Tap the orb and speak, or switch **Background ON** (bottom-right) and say **"Shraa suno"**.

Release signing is optional: `app/build.gradle.kts` reads `release.properties` only if the file exists.

## App layout

- **Home**: the SHRAA orb (tap to start/stop listening), status and the last thing heard.
- **Profile**: screen time, app usage, command stats, recent commands (clearable).
- **Settings** (top-right): Background assistant, Online AI, Voice, Connected Apps, Permissions.
- **Bottom bar**: Home, Profile, Background ON/OFF.

## Project structure (`com.abhirha.shraa`)

| Folder / file | Purpose |
|---|---|
| `MainActivity`, `ShraaApplication` | Navigation and app start |
| `AssistantController` | Ties wake, speech, AI, actions, voice and history together |
| `WakePopupActivity` | Wake pop-up card |
| `ai/` | `IntentClient` (Gemini call, retries, backup models, context), `AiProvider`, `ProviderApi`, `PlanSchema` (tool schema + system prompt), `ApiKeyStore` (encrypted key), `ShraaModels` (actions) |
| `actions/` | `ActionExecutor`, `LocalIntent` (offline parser), `NameMatch`, `DailyBrief`, `SpokenTime` |
| `voice/` | `SpeechInput`, `WakeWord`, `WakeWordEngine`, `QuietEarcon`, `TtsManager` |
| `service/` | `AssistantService` (microphone foreground service), `ShraaAccessibilityService`, `ShraaNotificationListener` |
| `apps/` | `AppCatalog`, `ConnectedAppsStore`, `MediaAccess` |
| `usage/`, `data/` | `UsageStatsHelper`; `SettingsStore`, `HistoryRepository` |
| `ui/` | Home, Profile, Settings, Connected Apps UI, Core orb, theme, components |

How it flows: **mic -> wake engine -> "Shraa suno" -> pop-up -> command speech -> Gemini picks actions -> SHRAA speaks the acknowledgement -> actions run -> history saved -> rest mode.**

## AI

SHRAA uses **Google Gemini only**. Default model `gemini-flash-latest`, with `gemini-flash-lite-latest` and `gemini-3.1-flash-lite` as automatic backups. Gemini is forced to answer through one structured function call, so replies are always machine-readable. Server errors are retried; rate limits move to the next model. Without a key or internet, the offline basic mode still handles simple commands.

The Gemini free tier may use content to improve Google products. Do not speak sensitive data to SHRAA on the free tier.

## Known limits (Android rules, not bugs)

- Background mode keeps the microphone open to hear the wake phrase, so Android shows its mic indicator. Idle audio is only checked locally for the wake phrase and is not stored or sent to Gemini.
- Wake detection uses `SpeechRecognizer` loops (not a dedicated hotword engine): more battery use, and some phones beep or restart sessions. A dedicated offline engine is a possible future improvement.
- Sending messages and operating other apps works through Accessibility ("Phone control"). Apps that draw their own screen (games) or block screen access (banking) cannot be operated; SHRAA says so.
- Android cannot switch Wi-Fi/Bluetooth directly; SHRAA opens the right settings page.
- Android 10+ may block opening apps from the background; "Display over other apps" helps on most devices.
- Android does not let one app grant another app's permissions. Games can only be opened.
- Offline AI answers are not included (no on-device model); offline speech needs an offline speech pack installed.

## Security (before you push to GitHub)

- The Gemini key is typed in the app and stored encrypted on the phone. It is not in the source code.
- **This folder contains `release.keystore` and `release.properties` (keystore passwords).** Do not push them. Add them to `.gitignore` and make sure they were never committed:
  ```
  echo "release.keystore" >> .gitignore
  echo "release.properties" >> .gitignore
  ```
  If they were already committed, remove them from the history and create a new keystore.
- Also keep `.gradle/` and `.cg/` out of the repository.

Full details: [`SHRAA_Final_Documentation.txt`](SHRAA_Final_Documentation.txt).
