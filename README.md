# Call Assistant

A personal Android app for **your own device and your own calls**: it detects when a call
starts and ends, records the call audio, and after you hang up shows a pop-up with suggested
follow-up messages you can send into WhatsApp or Telegram.

This is the **Phase 0 spike** — it proves the risky mechanics work on a real phone before the
rest is built. See [`../scope/call-assistant-app.md`](../scope/call-assistant-app.md) for the
full scope and roadmap.

## What this spike does today

- **Call detection** — a foreground service watches call state (`TelephonyCallback` on
  Android 12+, `PhoneStateListener` below) and reacts to off-hook / idle.
- **Audio capture** — records from the microphone while the call is active.
  On **speakerphone** this captures *both* voices (the remote party comes through your
  speaker and into your mic); off speaker it captures your side only. See the note on the
  Android 10 restriction below.
- **Post-call pop-up** — an overlay (`SYSTEM_ALERT_WINDOW`) appears after you hang up with
  the suggested drafts and WhatsApp / Telegram / Share buttons.
- **Contacts** — resolves the caller against your address book and saves unknown numbers.
- **Demo button** — fires the pop-up without a real call, so you can test the overlay fast.
- **Device & capture check** — reports this phone's make/model/Android version and which call-audio
  sources actually initialize on it, then a single real call confirms whether the chosen source
  captured the remote party (the pop-up shows the captured signal level). This is how we find out
  whether device-level both-sides capture works on your specific phone vs. needing speakerphone.

### Transcription and drafts (all free)
- **Groq for everything (when a Groq key is saved)**: calls are transcribed with Whisper on
  Groq's free tier (`CloudTranscriber.kt`, `whisper-large-v3`, falling back to
  `whisper-large-v3-turbo`) and replies are written by Groq too. This sends the call audio to
  Groq. Speaker labels still come from the offline speaker model, lined up with Whisper's
  segments by time (`CallTranscription.kt`). If Groq fails, the offline engine below is used.
- **Offline transcription** (`Transcriber.kt`) runs on the phone with
  [Vosk](https://alphacephei.com/vosk/). No key, no cost, audio never leaves the phone, and
  the recording is deleted once transcribed. Language is **Auto** by default: the first ~45 s
  go through both the Indian English and the Hindi model and the more confident one does the
  whole call. Mixed Hinglish is handled by whichever model wins, so expect rougher results.
- **Speakerphone vs phone at ear**: the app samples the call's audio route. On speakerphone
  both voices are recorded and lines are labelled by speaker; at the ear (or on a headset)
  only your side is recorded and the transcript and drafts say so.
- **Who said what** (`SpeakerLabeler.kt`): Vosk's speaker model fingerprints each utterance and
  the app splits them into two voices. Record your voice once on the main screen and your
  lines show as "You", the other person's as "Them"; otherwise "Speaker 1 / 2".
- **Drafts** (`DraftGenerator.kt`) work offline by default (rule-based). For smarter drafts,
  choose a free-tier provider in the app and paste a free key:
  - **Groq** (recommended): free key at console.groq.com/keys, default model
    `openai/gpt-oss-20b` (Groq retired the Llama 3.1 8B model in Aug 2026; the app
    switches to another available model by itself if this one is retired too).
  - **Google Gemini**: free key at aistudio.google.com/apikey, default model
    `gemini-flash-latest`. Google may use free-tier data to improve its products.
  - **Custom**: any OpenAI-compatible `/chat/completions` URL (OpenRouter free models, or a
    local Ollama/LM Studio server on your network; key optional).

  Cloud providers receive the transcript **text** only. If the call fails the app falls back
  to offline drafts.
- **Call history**: every call's transcript and drafts are kept (as JSON in app storage) and
  can be reopened and sent from the Call history screen.
- **Stays on**: re-arms itself after a restart or update; exempt it from battery saver on the
  main screen so Samsung doesn't stop it.

### Models (local builds)
CI downloads these into `app/src/main/assets/` before building. For a local build, unzip
them there yourself from https://alphacephei.com/vosk/models:
`vosk-model-small-en-in-0.4` → `vosk-model-en`, `vosk-model-small-hi-0.22` → `vosk-model-hi`,
`vosk-model-spk-0.4` → `vosk-model-spk`.

### Signing
Every CI build is signed with the same key so updates install over the old version. The key
comes from two repository secrets, `SIGNING_KEYSTORE_B64` (base64 of the keystore) and
`SIGNING_PASSWORD`. Without them the build still works but uses a throwaway key.

## The one hard constraint

Since **Android 10**, apps cannot record the remote party's voice through the normal
`VOICE_CALL` audio source on stock, non-rooted phones — Google closed it deliberately. The
only reliable way to capture both sides is **speakerphone + the mic**, which is what this app
does. Some vendor-specific call-recording routes exist but are fragile and break across
devices and OS updates; this spike does not rely on them.

> You are responsible for complying with call-recording / consent law in your jurisdiction.
> Recording a call that includes another person's voice often requires notifying or getting
> their consent. This is not legal advice.

## Getting an installable APK

The APK is **not built in this environment** (the Android SDK download host is blocked by the
network policy here). Build it one of these ways — both produce a sideloadable debug APK:

### Option A — GitHub Actions (no local setup)
Push this project to a GitHub repo. The workflow in `.github/workflows/build.yml` builds the
debug APK on every push and uploads it as a downloadable artifact
(`call-assistant-debug-apk`). Download it from the run's **Artifacts** section and sideload it.

### Option B — Android Studio / local Gradle
Open the project in Android Studio (it will fetch the SDK), or on a machine with the Android
SDK installed:
```bash
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Installing & testing on your phone

1. Sideload the APK (enable "Install unknown apps" for your file manager / browser).
2. Open the app → **Grant permissions** (mic, phone state, call log, contacts, notifications).
3. Tap **Allow display over other apps** and enable it.
4. Tap **Demo pop-up** to confirm the overlay shows and the share buttons open WhatsApp/Telegram.
5. Tap **Arm call monitor**, then make a real call **on speakerphone**. Hang up — the pop-up
   should appear. Check logcat (`adb logcat -s CallMonitor AudioRecorder`) to confirm detection
   and capture.

## Project layout

```
app/src/main/java/com/karan/callassistant/
  MainActivity.kt        permissions + arm/disarm + demo
  CallMonitorService.kt  foreground service, call-state detection, orchestration
  AudioRecorder.kt       mic capture to 16 kHz PCM
  Transcriber.kt         offline speech-to-text (Vosk), English/Hindi auto-pick
  SpeakerLabeler.kt      who-said-what on speakerphone calls
  CallHistory.kt         saved transcripts + drafts
  HistoryActivity.kt     call history list (+ CallDetailActivity)
  BootReceiver.kt        re-arm after restart / update
  AppSettings.kt         language, voiceprint, armed flag
  DraftGenerator.kt      follow-up drafts (offline rules or free-tier LLM) + LlmSettings
  OverlayManager.kt      post-call SYSTEM_ALERT_WINDOW pop-up
  ContactHelper.kt       caller lookup + save unknown numbers
  ShareHelper.kt         WhatsApp / Telegram / share-sheet intents
```
