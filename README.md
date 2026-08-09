# KANON / 観音

**音を、観る。**

KANON is an Android live-caption layer that turns English audio playing on the device into **live English captions + Japanese translation**, displayed over other apps.

<p align="center">
  <img src="docs/kanon_icon.png" width="220" alt="KANON icon">
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

## Requirements

- Android 10 / API 29 or later
- An app whose playback audio allows Android `AudioPlaybackCapture`
- Overlay permission (`SYSTEM_ALERT_WINDOW`)
- Screen/audio capture permission through Android MediaProjection
- OpenAI API key (BYOK)
- Internet connection

> KANON cannot capture audio from apps or content that block playback capture/DRM capture. It is not intended for calls or protected content.

## APK download

Current release:

**KANON v0.1.0**

https://github.com/Masato-Nasu/KANON/releases/download/v0.1.0/KANON-v0.1.0.apk

## Android installation and permissions

KANON is distributed as an APK, so the first launch requires several Android permissions and settings.

### 1. Install the APK

Download `KANON-v0.1.0.apk` on your Android device and open it.

If Android blocks the installation, allow APK installation for the app you used to open the file, such as Chrome or your file manager.

Depending on the Android version, the setting may be shown as:

- **Install unknown apps**
- **Allow from this source**
- **この提供元のアプリを許可**

After KANON has been installed, this permission can be turned off again.

### 2. Set your OpenAI API key

Open KANON, enter your own OpenAI API key, and tap **SAVE KEY**.

KANON uses BYOK (Bring Your Own Key). A shared API key is not embedded in the app.

The key is protected locally using Android Keystore.

> OpenAI API billing is separate from a ChatGPT subscription. The API account must have available credit for transcription and translation to work.

### 3. Allow “Display over other apps”

KANON displays its caption window on top of YouTube, radio apps, video apps, and other compatible apps.

Open the overlay permission screen from KANON and enable:

**Display over other apps / 他のアプリの上に表示 / 重ねて表示**

The exact menu differs by device, but it is usually found around:

`Settings → Apps → Special app access → Display over other apps → KANON`

### 4. Start KANON and approve screen/audio sharing

Tap **START KANON**.

Android will display a system confirmation for screen recording or screen sharing. KANON uses Android MediaProjection as part of the mechanism required to capture playback audio from compatible apps.

KANON is not using this permission to save a screen recording. Its purpose here is to obtain the playback-audio capture session used for live transcription.

When Android shows the confirmation dialog, tap **Start now / 今すぐ開始** or the equivalent button on your device.

### 5. Notification permission

On newer Android versions, Android may ask for notification permission.

KANON runs a foreground service while capture is active, so Android may show a persistent notification indicating that KANON is running.

If a notification permission dialog appears, allow it so the running state is easy to see.

### 6. Play English audio

With KANON running, play English audio in a compatible app.

The floating KANON window will build the session transcript as:

**English caption**

↓

**Japanese translation**

The caption window can be dragged to another position. Use `MIN` to collapse it and `OPEN` to expand it again.

When finished, tap **STOP**. This stops audio capture, transcription, translation, the overlay window, and the foreground service.

## If captions do not appear

Check the following:

- The OpenAI API key has been saved correctly
- The OpenAI API account has available credit
- The device is connected to the internet
- **Display over other apps** is allowed for KANON
- You approved the Android screen/audio sharing dialog after pressing **START KANON**
- The source app allows Android playback capture

The last point is especially important. `AudioPlaybackCapture` does **not** allow KANON to forcibly capture every app on Android.

Audio may not be available when the source app blocks playback capture, when DRM/protected content is being played, or for other protected audio such as calls.

## If Android blocks the overlay permission

Some Android devices apply additional restrictions to apps installed from APK files.

First check:

`Settings → Apps → KANON`

and look for an option such as:

- **Allow restricted settings**
- **制限付き設定を許可**

The wording and location vary by Android version and manufacturer.

If the normal Android settings screen still cannot grant the overlay permission, ADB can be used as a troubleshooting method from a connected PC:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb shell cmd appops set jp.masatolab.kanon ACCESS_RESTRICTED_SETTINGS allow
& $adb shell cmd appops set jp.masatolab.kanon SYSTEM_ALERT_WINDOW allow
& $adb shell cmd appops get jp.masatolab.kanon SYSTEM_ALERT_WINDOW
```

If the final command reports:

```text
SYSTEM_ALERT_WINDOW: allow
```

Android has granted the overlay app-op.

> ADB is only a fallback for devices where the normal settings UI does not allow the permission. Most users should not need it.

## Build on Windows 10/11

The repository includes a PowerShell 5.1-compatible build helper.

1. Install Android Studio / Android SDK.
2. Install Android SDK Platform 35 or newer.
3. Enable USB debugging if you want automatic installation.
4. Double-click `BUILD_AND_INSTALL.cmd`.

The script downloads Gradle 8.11.1 into `.build-tools/` when needed, builds the debug APK, and installs it through ADB when a device is connected.

## First run — quick version

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
