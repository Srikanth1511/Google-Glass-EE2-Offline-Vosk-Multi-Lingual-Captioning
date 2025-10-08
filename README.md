# Offline Transcription (Live Captions) for Google Glass EE2 using Vosk with Multi-Lingual Support
## The models that can be loaded are only small or Medium due to Limitation of Glass EE2 harware, hence the accuracy is BAD

This project contains a minimal Android application written in **Kotlin** for the
Google Glass Enterprise Edition 2 (EE2) headset.  The goal of the app is to
provide **continuous, offline captions** for spoken language on‑device.

## Key features

* **Offline speech recognition** – no Internet permission is requested.  The
  apps ASR engine is assumed to run entirely on device.  Models for
  **English** (`en`) and **Japanese** (`ja`) or other languages you prefer must be supplied under
  `app/src/main/assets/models/` and are copied into app‑private storage on
  first launch.  The repository only contains placeholder directories to keep
  the download size reasonable. Also live switch is not part of the project, only compiles `en` as of now. If you need other languages modify the code to point to that directory in core and ui files. 
  # You have to re-build the App if you want to add Languages !!
* **Continuous captions** – audio is captured from the Glass microphone, fed
  into the ASR engine and transcripts are rendered as a single
  rolling line of text.  Sessions can be started, paused and stopped with
  simple touch gestures.
* **Per‑session transcript files** – when a session is ended via a long press
  or by exiting the app, the current transcript is flushed to a `.txt` file
  under the app’s external files directory.
* **Foreground service** – audio capture and transcription run inside a
  foreground service to ensure the process keeps running while the device is in
  use.  A notification is shown with a microphone icon to comply with
  Android 8.1 restrictions.

## Gestures

The app leverages the Glass touchpad for simple controls:

| Gesture     | Action                        |
|------------|--------------------------------|
| **Tap**    | Toggle start / pause captioning |
| **Long‑press** | End session and save transcript |
| **Swipe down** | Exit the app immediately |

When captioning is active the *REC* status chip shows that audio is being
processed; when paused it reads *PAUSED*.  The language chip reflects the
currently active model (`EN` or `JA`), although language switching is not
implemented. The idea is to switch between languages, but it may be added later on.

## Code organisation

The source code is organised into logical packages:

| Directory                            | Purpose |
|--------------------------------------|---------|
| `ui/`                                | Activities and view logic |
| `audio/`                             | Audio capture helpers (not yet implemented) |
| `stt/`                               | Speech‑to‑text engine and foreground service |
| `core/`                              | Session management and utilities |

`ASREngine` is a lightweight Kotlin class that copies models from the `assets`
folder to private storage and provides stub methods for sending audio to a
native decoder (e.g. [Vosk](https://alphacephei.com/vosk/)【862107470703228†L25-L33】).  In
your implementation you should replace the stub methods with JNI calls into
your chosen ASR library.

## Building the project

1. Install the Android SDK and NDK.  Glass EE2 runs **Android 8.1 (API 27)**.
2. Open the project in **Android Studio**.  When prompted, install missing
   SDK components.
3. Connect your Glass EE2 device via USB and enable USB debugging.
4. Download the required [Vosk offline models](https://alphacephei.com/vosk/models)
   (for example `vosk-model-small-en-us-0.15` for English and
   `vosk-model-small-ja-0.22` for Japanese).  Extract each archive directly
   into `app/src/main/assets/models/<lang>/` so that files like `am/final.mdl`
   live immediately under that folder (i.e. no extra nested directory).
5. Build and run the `app` module.  The first launch will copy the models into
   `filesDir/models/en` and `filesDir/models/ja`.

## Notes

* Google’s native speech recognition on Glass EE2 only supports **English**【967913391672396†L499-L511】.  This app
  therefore relies on an offline ASR engine such as Vosk, which supports
  multiple languages including English and Japanese(replace this folder with other languages, if need so) and can run entirely on
  device.
  ## If you need English only look at the other repository that use android's native speech recognition.
* The provided code is ndosent have real
  decoder, implement voice activity detection (VAD), manage backpressure and
  latency, and handle battery/power constraints.
* If the UI remains on “Preparing model…” ensure that the Vosk model assets are
  present in the locations described above and relaunch the service.
