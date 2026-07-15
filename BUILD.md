# Building OpenCfMoto

You don't need to be a Kotlin/Android developer to get an installable APK. Pick one of the two
routes below. **Route 1 (cloud build) is the recommended one if you don't want to install the
Android toolchain.**

## Route 1 — Cloud build with GitHub Actions (no local setup)

The repo ships a workflow at [`.github/workflows/build.yml`](.github/workflows/build.yml) that
builds the app on GitHub's servers and gives you a downloadable APK.

1. Put this project in a GitHub repository (if it isn't already):
   ```bash
   cd open-cfmoto
   git init && git add . && git commit -m "OpenCfMoto"
   # create an empty repo on github.com, then:
   git remote add origin https://github.com/<you>/opencfmoto.git
   git push -u origin main
   ```
2. On GitHub open the **Actions** tab. The **Build debug APK** workflow runs automatically on every
   push (you can also press **Run workflow** to start it manually).
3. When the run finishes (green check), open it and download the **`opencfmoto-debug-apk`**
   artifact from the *Artifacts* section. Unzip it to get `app-debug.apk`.
4. Copy `app-debug.apk` to the phone and install it (allow "install from unknown sources"). This is
   a debug build — it installs alongside nothing else and needs no Play Store.

If the build **fails**, the red run's log shows the exact compile error and line — paste that back
and it can be fixed. That log is also how you'll catch any typo in new code before it ever reaches
the phone.

## Route 2 — Local build (Windows)

You need two things installed once:

- **JDK 21** (Temurin/Adoptium is fine) — the Gradle daemon is pinned to Java 21.
- **Android SDK** — easiest via **Android Studio** (Ladybug or newer). Install it, open this
  `open-cfmoto` folder once, and let it install the SDK + accept licenses. Android Studio also lets
  you press **Run ▶** to build-and-install straight to a plugged-in phone (enable USB debugging on
  the phone first).

To build from the command line instead (PowerShell, inside `open-cfmoto`):

```powershell
# Tell Gradle where the SDK is (only needed if Android Studio didn't create local.properties):
"sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk" | Out-File -Encoding ascii local.properties

.\gradlew.bat assembleDebug
```

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`. Install it with:

```powershell
# adb ships with the SDK platform-tools; phone connected + USB debugging on
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Getting logs off the phone

Every meaningful step logs to the on-screen log view. Use the **Share** button in the app to export
the full log to a file and send it to yourself — that exported log is the diagnostic channel for
this project (see `docs/00-README-HANDOFF.md`). For the CL-C450 Wi-Fi Direct work specifically, see
`docs/04-WIFI-P2P-CLC450.md` for exactly what to capture.
