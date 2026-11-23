import {
  TurboModuleRegistry,
  type CodegenTypes,
  type TurboModule,
} from 'react-native';

export interface HomeDevice {
  id: string;
  name: string;
  roomName?: string;
  isOn?: boolean;
  connectivity?: string;
  isControllable?: boolean;
}

export type OnOffChange = {
  deviceId: string;
  isOn: boolean;
};

export type MonitoringError = {
  message: string;
};

export interface Spec extends TurboModule {
  initialize(): Promise<boolean>;
  requestPermissions(): Promise<boolean>;
  getDevices(): Promise<HomeDevice[]>;
  setOnOff(deviceId: string, turnOn: boolean): Promise<void>;
  startOnOffMonitoring(): void;
  stopOnOffMonitoring(): void;
  readonly onOnOffChange: CodegenTypes.EventEmitter<OnOffChange>;
  readonly onMonitoringError: CodegenTypes.EventEmitter<MonitoringError>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('GoogleHomeDeviceApi');
