# Device Locker

Kiosk-lock app for a tablet or Oculus/Meta Quest headset. Pins the device to
a fullscreen lock screen that blocks home/recents/back/status-bar, and only
releases it when:

1. an admin flips the device to "unlocked" from the remote server, or
2. someone enters the on-device admin PIN (works offline).

## Pieces

- **`app/`** - the Android app (Kotlin). Device Owner + Lock Task (screen
  pinning) for the hard lock; polls the server every 10s for the remote
  flag; local PIN as an offline fallback.
- **`server/`** - tiny Node/Express server. One JSON file (`devices.json`)
  tracking `{ deviceId: { locked, lastSeen } }`, plus a one-page admin UI at
  `/` to lock/unlock any device from a phone browser.

## Server setup

```
cd server
npm install
cp .env.example .env
# edit .env: set a real ADMIN_KEY
npm start
```

Open `http://<server-lan-ip>:3000/` in a browser, paste the admin key, and
you'll see every device that has checked in with Lock/Unlock buttons.

## App setup

Edit the default server URL in `MainActivity.kt`
(`KEY_SERVER_URL` default, search `192.168.1.100`) to your server's LAN IP,
or change it later on-device via the hidden admin menu (tap the title text
7 times, enter the current PIN - default `1234` - then set the server URL
and a new PIN).

### Build

From Termux (not inside the Ubuntu proot - see
[[android_gradle_build_termux]]):

```
cd ~/projects/device-locker
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk \
  gradle assembleDebug -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Install + enroll as Device Owner

Device Owner mode requires no account on the device (factory reset or
fresh profile). Over `adb`:

```
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner com.harvest.devicelocker/.AdminReceiver
```

If that fails with "not allowed" it usually means there's a Google account
already on the device - remove all accounts (Settings > Accounts) or
factory-reset first, then retry.

Once it's Device Owner, launching the app pins it full-screen
automatically (`startLockTask()`), survives reboot (`BootReceiver`), and
the only way out is the remote flag or the admin PIN.

**Quest/Oculus note:** same `adb` flow works since Quest is Android-based,
but Meta's own Device Manager / kiosk settings can also pin or auto-launch
an app - check that it isn't fighting this app for who owns "home" if you
run both.

### Without Device Owner

If `dpm set-device-owner` isn't available (e.g. device already has
accounts and you don't want to reset it), the app still runs and still
polls the server/PIN, but `startLockTask()` without Device Owner shows
Android's normal "Screen pinning" system dialog once instead of locking
silently, and the user can always long-press Back+Recents to exit
pinning. Treat that path as a soft lock, not kiosk-grade.

## Tuning

- `POLL_INTERVAL_MS` in `MainActivity.kt` - how often it checks the server
  (default 10s).
- Admin PIN and server URL are stored per-device in SharedPreferences and
  changed via the hidden 7-tap admin menu - no rebuild needed to point a
  device at a different server.
- `ADMIN_KEY` in `server/.env` gates the lock/unlock endpoints and the
  admin page. The `/status/:deviceId` endpoint devices poll is
  intentionally unauthenticated (worst case a stranger reads a lock
  state); don't put anything more sensitive behind it without adding auth.
