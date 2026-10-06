# Device Locker

Kiosk-lock app for a tablet or Oculus/Meta Quest headset. Pins the device to
a fullscreen lock screen that blocks home/recents/back/status-bar, and only
releases it when:

1. an admin flips the device to "unlocked" from the remote server, or
2. someone enters the on-device admin PIN (works offline).

It can also push and silently install app updates to every enrolled
device - no store, no user tapping "install".

## Pieces

- **`app/`** - the Android app (Kotlin). Device Owner + Lock Task (screen
  pinning) for the hard lock; polls the server every 10s for the remote
  flag; local PIN as an offline fallback; checks every 5 min for a newer
  build and silently installs it.
- **`server/`** - tiny Node/Express server. One JSON file (`devices.json`)
  tracking `{ deviceId: { locked, lastSeen } }`, plus a one-page admin UI at
  `/` to lock/unlock any device from a phone browser, plus an `/app/publish`
  endpoint to push a new APK to every device.

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
run both. See the dedicated [Oculus/Meta Quest setup](#oculusmeta-quest-setup)
section below for the full enrollment flow on Quest specifically.

### QR-code enrollment (no adb/PC needed)

Android has a built-in enrollment flow that doesn't need `adb` at all -
useful when the only machine around is the one running this server/Termux
box, and the target device has a camera.

1. Factory-reset (or freshly unbox) the target device, don't go through
   normal setup.
2. On the Welcome screen, tap the same spot on screen **6 times**. This
   opens Android's QR provisioning flow.
3. Connect the target device to the same wifi as the box serving the APK.
4. It opens a camera - scan a QR code containing this JSON (generate one
   with `qrencode`, shown on a second screen for the target device's
   camera to read):
   ```json
   {
     "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.harvest.devicelocker/com.harvest.devicelocker.AdminReceiver",
     "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "http://<your-lan-ip>:<port>/app-debug.apk",
     "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "<base64 SHA-256 of the apk file>",
     "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
     "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
   }
   ```
   Serve the APK over plain HTTP from the same box (e.g.
   `python3 -m http.server <port>` in the folder holding it), and compute
   the checksum with:
   ```
   python3 -c "import hashlib,base64;print(base64.b64encode(hashlib.sha256(open('app-debug.apk','rb').read()).digest()).decode())"
   ```
5. Device downloads the APK, verifies the checksum, installs it, and sets
   Device Owner automatically. It launches straight into the locked kiosk
   screen.

Only works at initial device setup (same constraint as `adb
set-device-owner`), and target + server must be on the same LAN.

### Without Device Owner

If `dpm set-device-owner` isn't available (e.g. device already has
accounts and you don't want to reset it), the app still runs and still
polls the server/PIN, but `startLockTask()` without Device Owner shows
Android's normal "Screen pinning" system dialog once instead of locking
silently, and the user can always long-press Back+Recents to exit
pinning. Treat that path as a soft lock, not kiosk-grade.

## Oculus/Meta Quest setup

Quest (Quest 2/3/Pro) runs a heavily customized Android, so a few things
differ from a stock tablet:

1. **Enable Developer Mode first.** Create/join an organization in the
   Meta Quest mobile app (phone, not headset): Menu > Devices > pick your
   headset > Developer Mode > toggle on. Needs a Meta developer account
   (free) attached to the headset's Meta account. Reboot the headset
   after enabling.
2. **USB debugging.** Put on the headset, you'll get a one-time "Allow USB
   debugging?" prompt the first time a computer/adb connects - accept it.
   Quest's camera-based QR provisioning flow for Device Owner is
   unreliable/absent depending on firmware, so **use the `adb` route, not
   the QR-code route, for Quest.**
3. **adb over USB (simplest for Quest):** connect the headset to this
   Termux box with a USB-C cable,
   `pkg install android-tools`, then:
   ```
   adb devices                 # headset should show up, accept the
                                # in-headset prompt if you haven't yet
   adb install app/build/outputs/apk/debug/app-debug.apk
   adb shell dpm set-device-owner com.harvest.devicelocker/.AdminReceiver
   ```
   If `set-device-owner` refuses, it's almost always because a Meta
   account/profile already exists on that headset - this must be done on
   a freshly factory-reset headset, before finishing the Meta account
   setup screens, same as the tablet flow.
4. **adb over wifi instead of USB** also works once USB debugging has been
   accepted once - same `adb pair`/`adb connect` flow as a phone (see the
   wireless-debugging steps above), useful if the headset is already set
   up and you don't want to plug in a cable.
5. Sideloading (installing outside the Meta Store) is otherwise normal for
   Quest in Developer Mode - no extra flags needed beyond what's above.
6. Meta's own guardian/kiosk features (if you use Meta's Device Manager
   for an enterprise/kiosk headset fleet) can conflict with this app also
   trying to own "home" - if you're already using Meta's MDM for kiosk
   mode, you probably don't need this app's Device Owner layer at all;
   use one or the other, not both.

## Remote app update

Push a new build to every enrolled device without touching them:

```
cd ~/projects/device-locker
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk \
  gradle assembleDebug -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2

curl -X POST "http://<server-lan-ip>:3000/app/publish?versionCode=2" \
     -H "x-admin-key: <your ADMIN_KEY>" \
     -H "Content-Type: application/octet-stream" \
     --data-binary @app/build/outputs/apk/debug/app-debug.apk
```

`versionCode` must be strictly greater than what's already installed
(bump `versionCode` in `app/build.gradle.kts` before building, or just
pass a higher number here than last time). The server computes the
checksum itself from the uploaded bytes, so it never trusts the caller
for that.

Every enrolled device checks `/app/latest` every 5 minutes
(`UPDATE_CHECK_INTERVAL_MS` in `MainActivity.kt`). If the published
`versionCode` is newer, it downloads the APK, verifies the checksum, and
installs it via `PackageInstaller` with **no confirmation dialog** -
that's only possible because the app is the Device Owner, which is
allowed to skip the normal "install this update?" prompt for its own
installer sessions. The app restarts automatically after install and
re-pins itself.

This only works once a device is already enrolled as Device Owner; it's
not how you do the *first* install (use the adb or QR-code flow above for
that).

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
