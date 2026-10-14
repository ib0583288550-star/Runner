# היי עוזר — Hey Assistant

Android / Kotlin / Jetpack Compose app for six fixed music commands.

## Fixed commands
- Play Music / נגן מוזיקה
- Stop Music / עצור מוזיקה
- Next Song / השיר הבא
- Previous Song / השיר הקודם
- Volume Up / הגבר קול
- Volume Down / הנמך קול

The app sends Android media-key events for play/stop/next/previous and adjusts the music stream volume for volume commands.

## Important implementation status
- The fixed command matching and media actions are implemented in the service.
- **The current speech input still uses Android's SpeechRecognizer API** and therefore does not yet meet the requested independent bundled-engine requirement.
- The official Vosk model catalogue currently does not list a Hebrew model, so using Vosk alone would not satisfy Hebrew + English. A multilingual on-device model/engine must be integrated and tested before this can be called complete.
- Offline availability and always-on background microphone behavior are not yet verified on the target device.
- No APK should be considered ready until GitHub Actions reports a successful build and the resulting artifact is checked.

## Build
Open in Android Studio with JDK 17 and Android SDK 35, or use the GitHub Actions workflow.
