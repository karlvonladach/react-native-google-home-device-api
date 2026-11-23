# react-native-google-home-device-api

Google Home Device API for React Native. Control your smart home devices directly from your React Native application using Google's native Android Home APIs (`play-services-home`).

## Installation

```bash
npm install react-native-google-home-device-api
# or
yarn add react-native-google-home-device-api
```

## Prerequisites (Google Cloud & Google Home Console)

Before running your app on a physical Android device, configure your project in Google Cloud and the Google Home Developer Console:

1. **Enable the Home API:**
   - Open the [Google Cloud Console](https://google.com) and select your project.
   - Go to **APIs & Services → Library**, search for **Home API**, and click **Enable**.
2. **Configure OAuth Consent Screen & Test Users:**
   - Go to **APIs & Services → OAuth consent screen** (External).
   - Add the **exact Google account (`@gmail.com`)** signed into the Google Home app on your test device under **Test users**.
3. **Create an Android OAuth 2.0 Client ID:**
   - Get your debug SHA-1 fingerprint (`cd android && ./gradlew signingReport`).
   - Go to **APIs & Services → Credentials → + Create Credentials → OAuth client ID → Android**.
   - Enter your app's `applicationId` (e.g., `com.yourapp`) and your **SHA-1 certificate fingerprint**.
4. **Register in Google Home Developer Console:**
   - Open the [Google Home Developer Console](https://google.com) and import/link the same Google Cloud project with **Google Home APIs** enabled.

## Android Setup

### 1. Download & Install the Android Home SDK
Google distributes the Android Home SDK (`play-services-home` and `play-services-home-types`) as a downloadable archive rather than via public Maven Central:

1. Download the **Google Home SDK for Android** archive from the [Google Home Developers documentation / Developer Console](https://google.com).
2. Extract the archive and copy its Maven artifacts (`com/google/android/gms/...`) into your local Maven repository (`~/.m2/repository` on macOS/Linux or `C:\Users\<YourUsername>\.m2\repository` on Windows), or point Gradle to the extracted directory.
3. Add `mavenLocal()` (or your extracted SDK path) to your app's `android/build.gradle`:

```groovy
allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

### 2. Coroutines Version Alignment
`play-services-home:17.1.0` requires `kotlinx-coroutines` **1.11.0+** (`Mutex` Kotlin 2.2+ ABI). To prevent transitive dependencies from downgrading coroutines and causing a runtime `NoSuchMethodError`, add the following to your root `android/build.gradle`:

```groovy
subprojects {
    configurations.all {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0"
            )
        }
    }
}
```

## Usage

> **Note:** Google Home Device APIs require a **physical Android device** with Google Play Services and the Google Home app installed.

See the [example app](example/src/App.tsx) for on-demand connection, device
listing, connectivity-aware controls, and refreshing device state.

## API Reference

| Method | Returns | Description |
| :--- | :--- | :--- |
| `initialize()` | `Promise<void>` | Initializes the native `HomeClient` instance. |
| `requestPermissions()` | `Promise<boolean>` | Launches the Google Home structure & device permissions consent sheet. Resolves `true` if granted. |
| `getDevices()` | `Promise<HomeDevice[]>` | Lists all accessible devices across the user's Google Home structures. |
| `setOnOff(deviceId, on)` | `Promise<void>` | Sends an `OnOff` trait command (`true` = on, `false` = off) to the specified device. |
| `subscribeToOnOffChanges(onChange, onError)` | `{ remove(): void }` | Monitors reported on/off state; call `remove()` to unsubscribe. |

On Android, `getDevices()` includes `connectivity` (`ONLINE`, `PARTIALLY_ONLINE`,
`OFFLINE`, or `UNKNOWN`), `isControllable` (whether the supported On/Off trait
is reachable), and `isOn` when the trait reports its state. These are snapshots;
call `getDevices()` again to refresh them. A partially online device is online,
but its On/Off trait may still be unavailable.

When available, `deviceType` is the primary device type's SDK identifier.
For built-in Android SDK 17.1.0 types, `deviceTypeName` is an English,
human-readable name for that primary type (for example, `On/Off Light`).
It is absent for unrecognized types or when no primary type is reported;
applications can display `deviceType` as a fallback. Names are not localized.
If the SDK reports multiple distinct types, `deviceTypes` contains their
identifiers, including the primary type when reported. Either property may be
absent if the SDK does not provide the corresponding information. These are
identifiers, not localized display names. Type discovery uses Google's
experimental generic API and may change with SDK updates.

When updating the Android SDK, regenerate the type name mapping using the JDK
`jar` and `javap` tools. Extract `classes.jar` from each SDK AAR into separate
files, then run:

```bash
node scripts/generate-device-type-names.cjs <types-classes.jar> <home-classes.jar>
```

After initialization and permission approval, subscribe to live on/off state:

```tsx
import { subscribeToOnOffChanges } from 'react-native-google-home-device-api';

const subscription = subscribeToOnOffChanges(
  ({ deviceId, isOn }) => console.log(deviceId, isOn),
  ({ message }) => console.error('Device monitoring failed:', message)
);

// When the listener is no longer needed:
subscription.remove();
```

The Android subscription observes the supported On/Off light and plug-in unit
types. It emits their current reported state when monitoring starts and when
devices are re-observed, as well as subsequent changes; duplicate values are
possible. It does not report connectivity changes or act as a background
service. Keep the subscription while updates are needed, and refresh the device
list for connectivity or membership changes. If monitoring fails, the error
callback is invoked; remove and resubscribe to retry. Multiple subscribers
share one native monitor, which stops when the last subscription is removed.

## License

MIT
