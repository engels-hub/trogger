# trogger

A personal mileage log for one car, built for a courier who is self-employed in Latvia. The interface is in Latvian or Russian (switch in settings); reports and exports are always in Latvian. It is a small, offline take on apps like Driversnote.

## Features

- **Automatic trips.** The phone's motion sensors (Google Activity Recognition) wake the app when you start driving. GPS then confirms car speed: three fixes in a row at 20 km/h or faster. The trip ends by itself once the car has stood still for a set number of minutes. Without Play services, the app checks GPS every 20 seconds instead.
- **Manual shifts.** You can start and end a shift with odometer readings. A manual shift never ends on its own.
- **GPS distance.** A foreground service records distance with the screen off, filtering out jitter and GPS jumps. If the system kills the service, it picks up where it left off.
- **Ceļazīmes (waybills).** Each trip records date, times, route, purpose, platform, odometer start and end, km, and business or private.
- **Expenses.** You can log fuel (with litres), repairs, OCTA, KASKO, technical inspection, parking and so on, with receipt numbers. An expense can be marked as 100% business.
- **Business share.** This is business km ÷ total km, where total km comes from all odometer readings. Deductible = expenses marked 100% business + all other expenses × business share.
- **Export.** For a month or a whole year you get an `.xlsx` file with three sheets (Ceļazīmes, Izdevumi, Kopsavilkums), plus semicolon-separated CSV files for trips and expenses.

All data stays on the phone in SQLite. There is no account, no network use and no backup, so export regularly.

This is a logging tool, not tax advice. Check the current VID rules yourself.

## Install

Download the APK from the latest [release](https://github.com/engels-hub/trogger/releases/latest) and open it on the phone. Each release is signed with the same key, so a newer one installs over the old one and keeps your data.

## Releasing

Push a version tag. GitHub Actions builds a signed, shrunk release APK and attaches it to a release:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

Signing uses the repo secrets `TROGGER_KEYSTORE_B64`, `TROGGER_KEYSTORE_PASSWORD` and `TROGGER_KEY_PASSWORD`. Locally, `./gradlew assembleRelease` reads `~/.android/trogger-release.properties` instead.

## Build

You need JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On the phone:
- Allow location "all the time" and physical activity.
- Turn off battery optimisation for the app.
- On Xiaomi phones, also set battery to "No restrictions" and turn on Autostart.

Debug builds let any app send the trip detector a fake "driving started" broadcast, which is how it gets tested:

```bash
adb shell am broadcast -n lv.papart.trogger/.ActivityReceiver --ez debug_vehicle true
```
