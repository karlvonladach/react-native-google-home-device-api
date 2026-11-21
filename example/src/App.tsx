import { useEffect, useState } from 'react';
import { Alert, Button, FlatList, StyleSheet, Text, View } from 'react-native';
import GoogleHomeDeviceApi, {
  type HomeDevice,
} from 'react-native-google-home-device-api';

export default function App() {
  const [devices, setDevices] = useState<HomeDevice[]>([]);
  const [status, setStatus] = useState('Initializing...');

  useEffect(() => {
    const connect = async () => {
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

    connect();
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.status}>Status: {status}</Text>
      <FlatList
        data={devices}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <View style={styles.device}>
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

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 24,
    paddingTop: 60,
  },
  status: {
    marginBottom: 16,
  },
  device: {
    marginVertical: 8,
  },
});
