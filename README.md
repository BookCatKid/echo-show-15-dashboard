# Echo Show dashboard

Native Android home screen for the Amazon Echo Show 15 (1st gen). It draws a
Home Assistant dashboard, subscribes to the Home Assistant WebSocket API, and
shows calendar and smart-home widgets.

- Package: `com.echo.testlauncher`
- Label: `Home`
- `minSdk`/`targetSdk` 28

## Build

Needs Android SDK build-tools 34.0.0, platform `android-30`, and a JDK.

```sh
./build.sh
```

Output is `build/dashboard.apk` (signed) and `build/aligned.apk`.

Two paths in `build.sh` are machine-specific and need changing on another
machine: `JAVA_DIR` points at `/opt/homebrew/opt/openjdk@21`, and `R8_JAR`
points at an r8 jar outside this repository. The bundled `d8` in build-tools
can be used instead of r8 if it works on your JDK.

The signing key is not in this repository. `build.sh` creates
`signing/dashboard.jks` on first run if it is missing, so a fresh clone signs
with a new key, and that build will not install over one signed with the old
key.

## Install

```sh
adb install build/dashboard.apk
```

## Home button

`tools/home-redirect.sh` watches logcat for the stock HOME intent and starts
the dashboard instead. It runs as adb shell, needs no root, and does not
survive a reboot.

`tools/firetv-launch-bridge.sh` is a fixed-action bridge that launches the
Fire TV interface on request. It executes only the fixed action and never
request contents.

## Vendored libraries

`lib/` holds Java-WebSocket 1.6.0 and SLF4J 2.0.16 (MIT), with SHA-1 sidecars.
See `lib/README.md`. Icon drawables are covered by
`MATERIAL-ICONS-LICENSE.txt`.
