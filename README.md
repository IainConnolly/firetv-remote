# firetv-remote: Remote Remap for Fire TV

A small Fire TV app that makes the app shortcut buttons on the remote (Netflix,
Prime Video, Disney+ and so on) open an app you choose instead. You press the
button, the app detects it, and you choose what it opens.

Works on Fire TV devices that run Android apps (Fire OS), such as the Fire TV
Stick 4K. It does not work on Vega OS devices.

## How it works

The app runs an Android accessibility service. Fire TV remotes behave in one of
two ways, so it handles both:

1. **The button arrives as a key press.** The service blocks it and opens your
   chosen app.
2. **The system opens the button's app directly.** The service sees, say,
   Netflix come to the front and immediately opens your chosen app instead.
   If you open Netflix yourself from the home screen with the Select button,
   it's left alone. With this method, Netflix may flash briefly first.

   The replaced app is still running behind the chosen one, and a music app
   may start playing. For a few seconds after the switch the service pauses
   anything that plays and asks Android to close the replaced app. Android
   won't close a built-in app such as Amazon Music, but it stays paused.

## Install

1. Build it on this PC (see **Build**); release builds are signed with the key
   in **Signing**.
2. Install it with `adb install -r app/build/outputs/apk/release/app-release.apk`.
3. Every release build is signed with the same key, so new builds install over
   old ones and keep your mappings. A build signed with any other key (a CI
   build carries the runner's throwaway debug key) has to be uninstalled first,
   which loses the mappings and the keep-on grant.

## Set up

1. Open **Remote Remap** on the Fire TV.
2. Choose **Set up automatically** and follow the steps. In short: turn on
   *ADB debugging* in Settings → My Fire TV → Developer options, choose
   Continue, and when "Allow USB debugging?" appears, tick *Always allow* and
   choose OK. No computer is needed: the app connects to the Fire TV's own ADB
   (127.0.0.1:5555) and grants itself `WRITE_SECURE_SETTINGS`. It then switches
   its service on, and back on after every restart or update. You can turn ADB
   debugging off again afterwards.
3. Choose **Add mapping**. It starts blank.
4. Choose **Detect button** and press the remote button, so the app learns the
   key it sends, or the app it opens.
5. Choose the app to open instead.

You don't need to open the app after a restart: the system starts the service
at boot. The grant survives restarts and app updates, but not uninstalling.

### If automatic setup doesn't work

The app's **Other ways** dialog shows these with the device's own IP address:

- From a computer, or an Android phone with an ADB app, on the same Wi-Fi:

  ```
  adb connect <fire-tv-ip>:5555
  adb shell pm grant com.firetvremote android.permission.WRITE_SECURE_SETTINGS
  ```

  then open the app again.
- If your Fire OS version lists *Remote Remap* under Settings → Accessibility,
  turn it on there. Without the grant, some versions switch it off again after
  a restart.

If something doesn't work, open **Diagnostics**, press the button, choose
**Refresh**, and look at what the service saw.

## Build

```
./gradlew assembleRelease
```

Set `JAVA_HOME` to a JDK 17+ and `ANDROID_HOME` to the Android SDK first. The
APK is written to `app/build/outputs/apk/release/app-release.apk`. Its version
code comes from `GITHUB_RUN_NUMBER` (1 when unset): set it above the installed
build's so the new one installs as an update. CI builds every push with
`.github/workflows/build.yml` to check that it compiles.

## Test

```
scripts\e2e.ps1 [-Device <ip>:5555]
```

Drives the installed app on a real Fire TV over ADB, standing in for a remote
button by opening the app it launches: detecting a button by that app, refusing
the button's own app as its target, redirecting, two mappings that point at
each other, detecting again, the Detect timeout keeping the old button, backing
out of a new mapping, and the service coming back after a force-stop. It needs
Netflix, VLC and Plex installed and keep-on granted, and it removes the
mappings it adds. Reading the screen with `uiautomator` briefly switches other
accessibility services off, so it never reads the screen while the app is
listening for a button.

On an unrooted Fire TV no script can press a key the service sees (`input
keyevent` never reaches an accessibility service, and SELinux refuses writes to
the remote's input device), so these need the real remote:

1. **A key button:** Add mapping, Detect, press a button that sends a key (the
   Diagnostics screen shows which do), choose an app; press it from Home.
2. **Select:** open the mapped button's app from the home screen with Select;
   it stays open.
3. **Back from an overlay:** with that app open, bring up something on top
   (Alexa, the screensaver) and go back; it is still not replaced.

## Signing

Release builds are signed with the key named in `key.properties` at the repo
root:

```
storeFile=C:/Users/<you>/.firetv-remote/firetv-remote.jks
storePassword=...
keyAlias=firetv-remote
keyPassword=...
```

Both files are outside git and must be backed up privately: without them no
later build can update an installed copy. Without `key.properties`, release
builds fall back to the debug key.
