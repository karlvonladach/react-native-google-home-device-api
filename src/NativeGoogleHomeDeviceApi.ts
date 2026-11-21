import { TurboModuleRegistry, type TurboModule } from 'react-native';

export interface HomeDevice {
  id: string;
  name: string;
  roomName?: string;
  isOn?: boolean;
}

export interface Spec extends TurboModule {
  initialize(): Promise<boolean>;
  requestPermissions(): Promise<boolean>;
  getDevices(): Promise<HomeDevice[]>;
  setOnOff(deviceId: string, turnOn: boolean): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('GoogleHomeDeviceApi');
