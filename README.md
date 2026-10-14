# היי עוזר — Hey Assistant

Android / Kotlin / Jetpack Compose prototype for a voice assistant with the wake phrase **״היי עוזר״**.

## Current prototype
- Hebrew-first interface and a language selector mock-up for Hebrew/English.
- Foreground microphone service with a persistent notification that reports its current state.
- Attempts on-device speech recognition on Android 12+ and requests offline preference.
- Prototype commands: tell the time; stop listening. Other recognized phrases are reported as not yet configured.

## Important limitations
- Offline recognition depends on the device having a compatible on-device speech service and downloaded language packs. `EXTRA_PREFER_OFFLINE` alone cannot guarantee offline operation.
- The current prototype does not yet auto-start on headset connection, and the language selector is not wired into the service yet. These remain implementation tasks.
- Android restricts microphone foreground services started from the background. User permission and a foreground notification are required.
- Hebrew/English mixed-language recognition and always-on wake-word detection need device testing and a suitable offline engine/model before being considered complete.

## Build
Open this repository in Android Studio (JDK 17, Android SDK 35) and build the `app` module.
