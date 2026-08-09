# KANON / 観音

**音を、観る。**

KANON is an Android live-caption layer that turns English audio playing on the device into **live English captions + Japanese translation**, displayed over other apps.

<p align="center">
  <img src="docs/kanon-icon.png" width="220" alt="KANON icon">
</p>

## Concept

英語を聞き取れなかった時に、別の学習アプリへ移動するのではなく、**今使っているアプリの上に字幕そのものを後付けする**。

KANONはAndroidの `AudioPlaybackCapture` で、キャプチャを許可しているアプリの再生音声を取得し、OpenAI Realtime transcriptionで英語字幕化します。確定した英文は日本語へ翻訳し、同じフローティング字幕窓へ積み上げます。

名前の **KANON（観音）** は、「音を観る」という機能と、観音という言葉を重ねたものです。

## Features

- Live English transcription
- Japanese translation under each finalized English segment
- System-wide floating caption window
- **Full-session transcript**: the caption body keeps the entire session and scrolls automatically
- Draggable overlay position
- `MIN / OPEN` folding
- `STOP` ends capture, transcription, translation, and the foreground service
- BYOK (Bring Your Own OpenAI API Key)
- API key encrypted with Android Keystore
- Captured audio is not saved or exported by the app

## What KANON deliberately does not do

KANON started as **REWIND**, an experiment that kept the previous 10 seconds of audio for replay/transcription. During real use, the live-caption layer proved more useful than rewinding.

For KANON, the rewind button, replay function, 10-second audio ring buffer, NOW/FULL mode switching, and related UI were removed. The product is intentionally focused on one thing: **live comprehension**.

## Requirements

- Android 10 / API 29 or later
- An app whose playback audio allows Android `AudioPlaybackCapture`
- Overlay permission (`SYSTEM_ALERT_WINDOW`)
- Screen/audio capture permission through Android MediaProjection
- OpenAI API key (BYOK)
- Internet connection

> KANON cannot capture audio from apps or content that block playback capture/DRM capture. It is not intended for calls or protected content.

## Build on Windows 10/11

The repository includes a PowerShell 5.1-compatible build helper.

1. Install Android Studio / Android SDK.
2. Install Android SDK Platform 35 or newer.
3. Enable USB debugging if you want automatic installation.
4. Double-click `BUILD_AND_INSTALL.cmd`.

The script downloads Gradle 8.11.1 into `.build-tools/` when needed, builds the debug APK, and installs it through ADB when a device is connected.

## First run

1. Open KANON.
2. Enter your OpenAI API key and tap **SAVE KEY**.
3. Allow **Display over other apps**.
4. Tap **START KANON**.
5. Approve Android screen/audio sharing.
6. Play English audio in a compatible app.
7. The floating KANON window shows the session transcript in English + Japanese.

## Privacy / BYOK

The API key is not embedded in the APK. It is encrypted and stored on the device using Android Keystore.

KANON processes playback audio in memory and sends it to the transcription API while the service is running. The app does not provide audio recording, audio export, transcript export, or background archive functions.

## Current version

`v0.1.0`

This is the first version under the KANON name, derived from the REWIND prototype.

## Author

Masato Nasu / MASATO LAB
