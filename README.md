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
| `deviceHasTrait(device, traitId)` | `boolean` | Checks whether a listed device reports a trait identifier. |
| `setOnOff(deviceId, on)` | `Promise<void>` | Sends an `OnOff` trait command (`true` = on, `false` = off) to the specified device. |
| `subscribeToOnOffChanges(onChange, onError)` | `{ remove(): void }` | Monitors reported on/off state; call `remove()` to unsubscribe. |
| `getDevice(deviceId, selector?)` | device wrapper | Creates a wrapper for commands, attribute reads/writes, and event subscriptions. |
| `readTraitAttribute(deviceId, traitId, name, selector?)` | `Promise<unknown>` | Reads a reported trait attribute by its full trait ID and name. |
| `subscribeToTraitAttribute(deviceId, traitId, name, onChange, onError, selector?)` | `Promise<{ remove(): void }>` | Observes a reported attribute on one device type. |
| `executeTraitCommand(deviceId, traitId, name, options?, args?)` | `Promise<unknown>` | Executes a named command and returns its response, if any. |
| `writeTraitAttribute(deviceId, traitId, name, value, selector?)` | `Promise<void>` | Writes a supported trait attribute. |
| `subscribeToDeviceEvents(deviceId, onEvent, onError, selector?)` | `Promise<{ remove(): void }>` | Observes SDK trait events on one device type. |

On Android, `getDevices()` includes `connectivity` (`ONLINE`, `PARTIALLY_ONLINE`,
`OFFLINE`, or `UNKNOWN`) and `traitIds`, a sorted, deduplicated list of the full
trait identifiers reported across its device types and parts. The list is empty
when no traits are reported. These are snapshots; call `getDevices()` again to
refresh them. A reported trait is not necessarily reachable or able to execute
a particular command, especially on a partially online device.

```tsx
import { deviceHasTrait, TraitIds } from 'react-native-google-home-device-api';

if (deviceHasTrait(device, TraitIds.OnOffTrait)) {
  // The device reports an OnOff trait.
}
```

For compatibility, `isOn` is also included when an On/Off light or plug-in
unit reports its state. Use generic attribute reads for other device types.

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

When updating the Android SDK, regenerate the type name mapping, factory registry,
command descriptors, trait IDs and attribute writers. Extract `classes.jar` from each SDK
AAR into separate files, then use JDK 17+ and matching Kotlin standard-library
and reflection JARs:

```bash
node scripts/generate-device-type-names.cjs <types-classes.jar> <home-classes.jar>
java -cp "<home-classes.jar>;<types-classes.jar>;<kotlin-stdlib.jar>" scripts/GenerateTraitIds.java <types-classes.jar> src/TraitIds.ts
java -cp "<home-classes.jar>;<types-classes.jar>;<kotlin-stdlib.jar>;<kotlin-reflect.jar>" scripts/GenerateAttributeWriters.java <types-classes.jar> android/src/main/java/com/googlehomedeviceapi/SdkAttributeWriters.kt
```

The classpath above uses Windows separators; use `:` instead of `;` on Unix.

### Trait access and events

The Android wrapper registers the built-in types and traits from SDK 17.1.0.
The experimental generic API reads attributes reported by the selected device
type. A device can have more than one type or part: pass
`{ deviceTypeId, partId }` when necessary. Operations reject ambiguous or
missing matches rather than selecting a type arbitrarily.

`TraitIds` provides named IDs for all built-in traits in SDK 17.1.0, such as
`TraitIds.OnOffTrait`. Google traits use a `Google` prefix, for example
`TraitIds.GoogleChimeTrait`, to distinguish them from Matter traits such as
`TraitIds.ChimeTrait`. Map values are full ID strings and can be passed directly
to generic methods; raw ID strings still work for custom or future traits.
The exported `TraitId` type is the union of the built-in ID values.
`ON_OFF_TRAIT_ID` remains available for compatibility.

Named commands use SDK command descriptors. Use the lower-camel command name
(such as `moveToLevel`) or its SDK descriptor name (`MoveToLevelCommand`) and
provide an object keyed by SDK field names. Integer fields accept integers;
pass values outside JavaScript's safe integer range as decimal strings. Enum
fields accept their SDK value name or a non-negative integer, bitmaps use a
non-negative integer, bytes use arrays of 0–255, and structs and lists use
objects and arrays. Omit optional fields that are not being supplied. Explicit
null command fields are rejected because the SDK's generic serializer omits
them; dynamic field types are not supported. The SDK and device determine
whether a command is supported. Command responses use the same serialization
as reads and events and may reject for unsupported payload types. In that case
the command may already have executed; do not retry without checking the device.

Named attribute writes use the SDK's typed setters for writable built-in
attributes. Read-only or unsupported attributes reject. Values use numbers,
booleans and strings; use decimal strings for integers outside JavaScript's safe
integer range. Enum values use SDK names or numeric values, bitmaps use a
non-negative integer, byte strings use arrays of 0–255, and lists use arrays.
Structs use objects keyed by SDK constructor parameter names. Supply every
non-optional field (including explicit `null` for nullable fields); omit an SDK
`OptionalValue` field to leave it absent. Invalid fields and unsupported bitmap
bits reject instead of being silently dropped. The selected device must report
the attribute as supported. Only OnOff currently has a typed facade.
The example app uses the generic read and command APIs for OnOff devices; the
`onOff` attribute itself is read-only. Devices with multiple matching types or
parts require a selector, which the example app does not choose.

```tsx
import { getDevice, TraitIds } from 'react-native-google-home-device-api';

const device = getDevice(deviceId, { deviceTypeId, partId });
await device.executeCommand(TraitIds.OnOffTrait, 'on');
await device.traits.onOff.off();
// A parameterized command (the device must support the trait and command):
await device.executeCommand(TraitIds.LevelControlTrait, 'moveToLevel', {
  level: 100,
  transitionTime: 10,
  optionsMask: 0,
  optionsOverride: 0,
});
const state = await device.readAttribute(TraitIds.OnOffTrait, 'onOff');
await device.traits.onOff.setOnTime(10);
await device.writeAttribute(
  TraitIds.ThermostatTrait,
  'occupiedHeatingSetpoint',
  2100
);

const subscription = await device.subscribeToEvents(
  (event) => console.log(event.eventName, event.payload),
  (error) => console.error(error.message)
);
// When events are no longer needed:
subscription.remove();
```

Trait events are distinct from attribute changes. Event monitoring observes a
snapshot of the selected type, is not a background service, and must be
restarted after an error or device/type changes. It exposes the event ID, name,
part, and event number; 64-bit integers are strings to preserve precision.
Read values and event payloads include JSON-compatible primitives, lists and
field maps. Unsupported values, including SDK struct objects, cause an explicit
read or subscription error rather than being silently converted.

After initialization and permission approval, subscribe to live on/off state:

```tsx
import { getDevice, TraitIds } from 'react-native-google-home-device-api';

const subscription = await getDevice(deviceId).subscribeToAttribute(
  TraitIds.OnOffTrait,
  'onOff',
  (value) => console.log(deviceId, value),
  ({ message }) => console.error('Device monitoring failed:', message)
);

// When the listener is no longer needed:
subscription.remove();
```

The Android subscription emits the current reported value and subsequent
changes for the selected device and attribute. It does not report connectivity
or membership changes or act as a background service. Refresh the device list
and manage subscriptions as membership changes. If monitoring fails, the error
callback is invoked; remove and resubscribe to retry. The legacy
`subscribeToOnOffChanges` API remains available for On/Off lights and plugs.

## License

MIT
