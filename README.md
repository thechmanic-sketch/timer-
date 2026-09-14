# Focus Timer

Android app: timer + stopwatch with camera focus readout, minute/second beeps, and 6 fixed daily alarms.

## Features

- **Timer & Stopwatch** — switch tabs, enter minutes for the timer.
- **Camera focus readout** — opens the back camera, shows live preview plus the lens's
  autofocus distance (in diopters) and AF state, read straight from Camera2's `CaptureResult`.
- **Beeps** — beeps once every full minute while running; during a timer's **last 20 seconds**
  it beeps every second instead, ending with a longer beep at zero.
- **SAST clock** — a live clock at the top of the screen showing the current time in
  South Africa Standard Time (UTC+2), regardless of the phone's own time zone.
- **Screen stays on** while the app is open (`FLAG_KEEP_SCREEN_ON`).
- **6 daily alarms**, fixed at 5:30, 9:00, 12:00, 15:00, 18:00, 21:00 every day — full-screen,
  ringtone + vibration, works even if the phone is locked, and reschedules itself after firing
  and after a reboot.

## Building the APK

This sandbox environment cannot reach Google's Android SDK/Maven servers, so the APK
could not be compiled here. To build it yourself (one-time, ~5 minutes):

1. Install [Android Studio](https://developer.android.com/studio) (bundles the SDK) — or just
   the command-line SDK if you prefer.
2. Open this project folder in Android Studio and let it sync (first sync downloads the
   Android SDK + AndroidX libraries automatically).
3. Build → Build Bundle(s)/APK(s) → Build APK(s), or run:
   ```
   ./gradlew assembleDebug
   ```
   The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
4. Copy that APK to your phone and install it (you'll need to allow "install unknown apps"
   for whatever app you use to open it).

## Permissions it will ask for

- Camera (for the focus readout)
- Notifications / exact alarms (for the 6 daily alarms and to show them precisely on time)

On first launch, also check your phone's battery settings and allow this app to ignore
battery optimization / run in the background — otherwise Android may prevent the alarms
from firing reliably.
