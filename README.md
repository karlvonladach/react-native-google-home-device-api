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

```tsx
import React, { useState } from 'react';
import { Alert, Button, FlatList, Text, View } from 'react-native';
import GoogleHomeDeviceApi, {
  type HomeDevice,
} from 'react-native-google-home-device-api';

export default function App() {
  const [devices, setDevices] = useState<HomeDevice[]>([]);
  const [status, setStatus] = useState('Disconnected');

  const connectAndLoad = async () => {
    try {
      await GoogleHomeDeviceApi.initialize();
      const granted = await GoogleHomeDeviceApi.requestPermissions();

      if (!granted) {
        setStatus('Permission denied');
        return;
      }

      setStatus('Connected');
      const list = await GoogleHomeDeviceApi.getDevices();
      setDevices(list);
    } catch (err: any) {
      setStatus(`Error: ${err?.message ?? err}`);
      Alert.alert('Google Home Error', String(err?.message ?? err));
    }
  };

  return (
    <View style={{ flex: 1, padding: 24, paddingTop: 60 }}>
      <Text style={{ marginBottom: 12 }}>Status: {status}</Text>
      <Button title="Connect Google Home" onPress={connectAndLoad} />

      <FlatList
        data={devices}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <View style={{ marginVertical: 10 }}>
            <Text>{item.name}</Text>
            <Button
              title="Turn On"
              onPress={() => GoogleHomeDeviceApi.setOnOff(item.id, true)}
            />
          </View>
        )}
      />
    </View>
  );
}
```

## API Reference

| Method | Returns | Description |
| :--- | :--- | :--- |
| `initialize()` | `Promise<void>` | Initializes the native `HomeClient` instance. |
| `requestPermissions()` | `Promise<boolean>` | Launches the Google Home structure & device permissions consent sheet. Resolves `true` if granted. |
| `getDevices()` | `Promise<HomeDevice[]>` | Lists all accessible devices across the user's Google Home structures. |
| `setOnOff(deviceId, on)` | `Promise<void>` | Sends an `OnOff` trait command (`true` = on, `false` = off) to the specified device. |

On Android, `getDevices()` includes `connectivity` (`ONLINE`, `PARTIALLY_ONLINE`,
`OFFLINE`, or `UNKNOWN`), `isControllable` (whether the supported On/Off trait
is reachable), and `isOn` when the trait reports its state. These are snapshots;
call `getDevices()` again to refresh them. A partially online device is online,
but its On/Off trait may still be unavailable.

## License

MIT
