# Echo Show dashboard

Native Android home screen for the Amazon Echo Show 15 (1st gen). It draws a
Home Assistant dashboard, subscribes to the Home Assistant WebSocket API, and
shows calendar, weather and smart-home widgets.

This is an example custom dashboard you can run on a Show rooted with
[hoya-runtime-root](https://github.com/BookCatKid/hoya-runtime-root).

- Package: `com.bookcatkid.launcher`
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

As far as I know, the only way to get an APK onto the Echo Show is LAT.

## Home button

`tools/home-redirect.sh` watches logcat for the stock HOME intent and starts
the dashboard instead. It runs as shell and needs no root.

```sh
adb push tools/home-redirect.sh /data/local/tmp/echo-dashboard-home-redirect.sh
adb shell chmod 700 /data/local/tmp/echo-dashboard-home-redirect.sh
adb shell sh /data/local/tmp/echo-dashboard-home-redirect.sh
```

To start it at boot, install `tools/home-redirect.rc` as
`/odm/etc/init/home-redirect.rc`. That one-time write needs root. The service
then runs as shell on every boot.

## Vendored libraries

`lib/` holds Java-WebSocket 1.6.0 and SLF4J 2.0.16 (MIT), with SHA-1 sidecars.
See `lib/README.md`. Icon drawables are covered by
`MATERIAL-ICONS-LICENSE.txt`.
