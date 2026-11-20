# WatchLink — Galaxy Watch 6 Classic companion

A phone app plus a watch app that talk over the Wear OS Data Layer:

| Feature | Phone (`:mobile`) | Watch (`:wear`) |
|---|---|---|
| Notifications from all apps | NotificationListenerService forwards every notification (per-app mute list) | Alerts and vibrates, scrollable list (rotating bezel), detail view |
| Messages and replies | Fills the app's own RemoteInput (WhatsApp, Messages, Telegram…) | Quick replies, smart replies, voice, keyboard and emoji |
| Notification actions | Fires any action, dismisses, opens on phone | Chips for every action |
| Calls | Caller name/photo, answer / decline / end / silence, decline-with-SMS | Full-screen incoming-call screen with timer |
| Watch faces | Picks a Watch Face Format APK + token and streams it to the watch | Installs via the Watch Face Push API (Wear OS 6) and sets it active |

Modules: `shared` (wire protocol), `mobile`, `wear`, `wearfacepush` (watch-face install logic),
`sample-watchface` ("WatchLink Classic", a Watch Face Format face).

## Get the APKs
GitHub Actions (`.github/workflows/watchlink.yml`) builds on every push that touches `WatchLink/`.
Open the run → **Artifacts → WatchLink-apks**, which contains:
- `WatchLink-phone.apk`
- `WatchLink-watch.apk`
- `WatchLink-Classic-watchface.apk` + `.token`

To build locally, install JDK 17 + the Android SDK, then run `scripts/build_watchfaces.sh && ./gradlew :mobile:assembleRelease :wear:assembleRelease`.

## Install
1. **Phone:** install `WatchLink-phone.apk` (allow "Install unknown apps").
2. **Watch:** enable Developer options on the watch (Settings › About watch › Software › tap Software version 5×).
   Then turn on ADB debugging and Wireless debugging, and run:
   `adb pair <ip:pair-port>` → `adb connect <ip:port>` → `adb install WatchLink-watch.apk`.
3. Both APKs are signed with the same key (`keystore/watchlink-dev.jks`). **This is required**: the Data
   Layer only connects apps with the same package name *and* signature. Always install both from the same build.

## Phone setup (Status tab)
- Grant **Notification access**, Phone, Call log, Contacts, Answer calls, SMS and Notifications.
- Exempt WatchLink from battery optimisation (Samsung: also set the app to *Unrestricted* in Battery settings).
- To avoid duplicate alerts, turn off notification mirroring for those apps in Galaxy Wearable › Watch settings › Notifications.

## Watch setup
- Open WatchLink once and allow notifications.
- In Settings, tap **Allow setting watch faces active**.
- On Android 14-based watch firmware you may also need to allow *full-screen notifications* for WatchLink (for the call screen).

## Watch faces
Watch Face Push needs Wear OS 6 (One UI 8 Watch). Every watch-face APK must:
- be named `com.watchlink.companion.watchfacepush.<name>`
- be pure Watch Face Format (no code)
- come with a validation token from Google's `validator-push-cli`

`scripts/build_watchfaces.sh` does this for the sample face. The phone app includes that face as **Install sample watch face**.

## Limitations
- The watch must be paired with the phone through Galaxy Wearable, which sets up the Data Layer.
- Some apps' actions open an activity on the phone. Android 14+ may block that from the background.
- The signing key is a development key committed to the repo. For anything beyond personal use, replace it.
