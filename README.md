# Call Assistant — Phase 0 spike

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

### Placeholders wired for the next phases
- **Transcription** (`Transcriber.kt`) returns a placeholder. Phase 1 drops in an open-source
  **on-device** engine (whisper.cpp or Vosk) so audio never leaves the phone.
- **Draft generation** (`DraftGenerator.kt`) uses offline templates. Phase 3 calls an LLM
  (Claude / GPT) over its HTTPS API with the transcript text to produce context-aware drafts.
  (The app talks to the model via its API, not MCP — MCP plugs tools into a model, it isn't
  how an app calls one.)

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
  Transcriber.kt         on-device ASR (placeholder; Phase 1 hook)
  DraftGenerator.kt      follow-up drafts (templates; Phase 3 LLM hook)
  OverlayManager.kt      post-call SYSTEM_ALERT_WINDOW pop-up
  ContactHelper.kt       caller lookup + save unknown numbers
  ShareHelper.kt         WhatsApp / Telegram / share-sheet intents
```
