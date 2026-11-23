import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import GoogleHomeDeviceApi, {
  subscribeToOnOffChanges,
  type HomeDevice,
} from 'react-native-google-home-device-api';

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function canControl(device: HomeDevice): boolean {
  return (
    (device.connectivity === 'ONLINE' ||
      device.connectivity === 'PARTIALLY_ONLINE') &&
    device.isControllable === true &&
    device.isOn !== undefined
  );
}

export default function App() {
  const [devices, setDevices] = useState<HomeDevice[]>([]);
  const [status, setStatus] = useState('Not connected');
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(false);
  const [pendingIds, setPendingIds] = useState<string[]>([]);
  const [monitorVersion, setMonitorVersion] = useState(0);
  const eventVersions = useRef<Record<string, number>>({});

  useEffect(() => {
    if (!connected) return undefined;
    let active = true;
    try {
      const subscription = subscribeToOnOffChanges(
        ({ deviceId, isOn }) => {
          if (!active) return;
          eventVersions.current[deviceId] =
            (eventVersions.current[deviceId] ?? 0) + 1;
          setDevices((current) =>
            current.map((device) =>
              device.id === deviceId ? { ...device, isOn } : device
            )
          );
        },
        (error) => {
          if (!active) return;
          setStatus('Live updates unavailable');
          Alert.alert(
            'Google Home Error',
            `${error.message}\nRefresh devices to retry.`
          );
        }
      );
      return () => {
        active = false;
        subscription.remove();
      };
    } catch (error: unknown) {
      setStatus('Live updates unavailable');
      Alert.alert(
        'Google Home Error',
        `${errorMessage(error)}\nRefresh devices to retry.`
      );
      return undefined;
    }
  }, [connected, monitorVersion]);

  const loadDevices = async () => {
    const versions = { ...eventVersions.current };
    const list = await GoogleHomeDeviceApi.getDevices();
    setDevices((current) =>
      list.map((device) => {
        const previous = current.find((item) => item.id === device.id);
        return previous &&
          eventVersions.current[device.id] !== versions[device.id]
          ? { ...device, isOn: previous.isOn }
          : device;
      })
    );
  };

  const connect = async () => {
    setLoading(true);
    setStatus('Connecting...');
    try {
      await GoogleHomeDeviceApi.initialize();
      const granted = await GoogleHomeDeviceApi.requestPermissions();
      if (!granted) {
        setStatus('Permission denied');
        return;
      }
      await loadDevices();
      setConnected(true);
      setStatus('Connected');
    } catch (error: unknown) {
      setStatus('Connection failed');
      Alert.alert('Google Home Error', errorMessage(error));
    } finally {
      setLoading(false);
    }
  };

  const refresh = async () => {
    setLoading(true);
    try {
      await loadDevices();
      setStatus('Connected');
      setMonitorVersion((version) => version + 1);
    } catch (error: unknown) {
      setStatus('Refresh failed');
      Alert.alert('Google Home Error', errorMessage(error));
    } finally {
      setLoading(false);
    }
  };

  const toggle = async (device: HomeDevice) => {
    if (!canControl(device) || loading || pendingIds.includes(device.id)) {
      return;
    }
    const nextState = !device.isOn;
    const eventVersion = eventVersions.current[device.id];
    setPendingIds((current) => [...current, device.id]);
    try {
      await GoogleHomeDeviceApi.setOnOff(device.id, nextState);
      setDevices((current) =>
        current.map((item) =>
          item.id === device.id &&
          eventVersions.current[device.id] === eventVersion
            ? { ...item, isOn: nextState }
            : item
        )
      );
    } catch (error: unknown) {
      Alert.alert('Device Error', errorMessage(error));
    } finally {
      setPendingIds((current) => current.filter((id) => id !== device.id));
    }
  };

  return (
    <View style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <Text style={styles.title}>My Home</Text>
      <Text style={styles.status}>{status}</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={connected ? 'Refresh devices' : 'Connect to home'}
        accessibilityState={{ disabled: loading || pendingIds.length > 0 }}
        disabled={loading || pendingIds.length > 0}
        onPress={connected ? refresh : connect}
        style={[styles.connectButton, loading && styles.disabled]}
      >
        <Text style={styles.connectText}>
          {loading
            ? 'Please wait...'
            : connected
              ? 'Refresh devices'
              : 'Connect to home'}
        </Text>
      </Pressable>
      {connected && (
        <FlatList
          data={devices}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          ListEmptyComponent={
            <Text style={styles.status}>No devices found.</Text>
          }
          renderItem={({ item }) => {
            const offline = item.connectivity === 'OFFLINE';
            const available = canControl(item);
            const disabled =
              !available || loading || pendingIds.includes(item.id);
            const detail = offline
              ? 'Offline'
              : item.connectivity !== 'ONLINE' &&
                  item.connectivity !== 'PARTIALLY_ONLINE'
                ? 'Connectivity unknown'
                : !item.isControllable
                  ? 'Control unavailable'
                  : item.isOn === undefined
                    ? 'State unavailable'
                    : null;

            return (
              <View
                style={[styles.device, !available && styles.deviceUnavailable]}
              >
                <View style={styles.deviceInfo}>
                  <Text style={styles.deviceName}>{item.name}</Text>
                  {(item.deviceTypeName || item.deviceType) && (
                    <Text style={styles.deviceDetail}>
                      {item.deviceTypeName || item.deviceType}
                    </Text>
                  )}
                  {detail && <Text style={styles.deviceDetail}>{detail}</Text>}
                </View>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`${item.isOn ? 'Turn off' : 'Turn on'} ${item.name}`}
                  accessibilityState={{ disabled }}
                  disabled={disabled}
                  onPress={() => toggle(item)}
                  style={[styles.toggle, disabled && styles.disabled]}
                >
                  <Text style={styles.toggleText}>
                    {item.isOn ? 'Off' : 'On'}
                  </Text>
                </Pressable>
              </View>
            );
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#10151d',
    paddingHorizontal: 24,
    paddingTop: 60,
  },
  title: { color: '#f5f7fa', fontSize: 30, fontWeight: '700' },
  status: { color: '#b8c2d0', fontSize: 15, marginTop: 8 },
  connectButton: {
    alignSelf: 'flex-start',
    backgroundColor: '#8bb8ff',
    borderRadius: 10,
    marginTop: 20,
    paddingHorizontal: 16,
    paddingVertical: 11,
  },
  connectText: { color: '#10151d', fontSize: 15, fontWeight: '600' },
  list: { paddingTop: 24, paddingBottom: 24 },
  device: {
    alignItems: 'center',
    backgroundColor: '#202a37',
    borderRadius: 12,
    flexDirection: 'row',
    marginBottom: 10,
    minHeight: 72,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  deviceUnavailable: { opacity: 0.6 },
  deviceInfo: { flex: 1, paddingRight: 12 },
  deviceName: { color: '#f5f7fa', fontSize: 19, fontWeight: '600' },
  deviceDetail: { color: '#c4cedb', fontSize: 13, marginTop: 3 },
  toggle: {
    alignItems: 'center',
    backgroundColor: '#8bb8ff',
    borderRadius: 8,
    justifyContent: 'center',
    minHeight: 40,
    minWidth: 48,
    paddingHorizontal: 10,
  },
  toggleText: { color: '#10151d', fontSize: 14, fontWeight: '700' },
  disabled: { opacity: 0.5 },
});
