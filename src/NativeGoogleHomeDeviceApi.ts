import {
  TurboModuleRegistry,
  type CodegenTypes,
  type TurboModule,
} from 'react-native';

export interface HomeDevice {
  id: string;
  name: string;
  deviceType?: string;
  deviceTypeName?: string;
  deviceTypes?: string[];
  traitIds: string[];
  roomName?: string;
  isOn?: boolean;
  connectivity?: string;
}

export type OnOffChange = {
  deviceId: string;
  isOn: boolean;
};

export type MonitoringError = {
  message: string;
};

export type NativeDeviceEvent = {
  subscriptionId: string;
  deviceId: string;
  deviceTypeId: string;
  partId?: string;
  eventId: string;
  eventName: string;
  eventNumber: string;
  payloadJson: string;
};

export type NativeDeviceEventError = {
  subscriptionId: string;
  message: string;
};

export type NativeAttributeChange = {
  subscriptionId: string;
  valueJson: string;
};

export interface Spec extends TurboModule {
  initialize(): Promise<boolean>;
  requestPermissions(): Promise<boolean>;
  getDevices(): Promise<HomeDevice[]>;
  setOnOff(deviceId: string, turnOn: boolean): Promise<void>;
  executeTraitCommand(
    deviceId: string,
    deviceTypeId: string,
    partId: string,
    traitId: string,
    commandName: string,
    argumentsJson: string,
    useTimedCommand: boolean
  ): Promise<string>;
  writeTraitAttribute(
    deviceId: string,
    deviceTypeId: string,
    partId: string,
    traitId: string,
    attributeName: string,
    valueJson: string
  ): Promise<void>;
  readTraitAttribute(
    deviceId: string,
    deviceTypeId: string,
    partId: string,
    traitId: string,
    attributeName: string
  ): Promise<string>;
  startDeviceEventMonitoring(
    subscriptionId: string,
    deviceId: string,
    deviceTypeId: string,
    partId: string
  ): Promise<void>;
  stopDeviceEventMonitoring(subscriptionId: string): void;
  startAttributeMonitoring(
    subscriptionId: string,
    deviceId: string,
    deviceTypeId: string,
    partId: string,
    traitId: string,
    attributeName: string
  ): Promise<void>;
  stopAttributeMonitoring(subscriptionId: string): void;
  startOnOffMonitoring(): void;
  stopOnOffMonitoring(): void;
  readonly onOnOffChange: CodegenTypes.EventEmitter<OnOffChange>;
  readonly onMonitoringError: CodegenTypes.EventEmitter<MonitoringError>;
  readonly onDeviceEvent: CodegenTypes.EventEmitter<NativeDeviceEvent>;
  readonly onDeviceEventError: CodegenTypes.EventEmitter<NativeDeviceEventError>;
  readonly onAttributeChange: CodegenTypes.EventEmitter<NativeAttributeChange>;
  readonly onAttributeMonitoringError: CodegenTypes.EventEmitter<NativeDeviceEventError>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('GoogleHomeDeviceApi');
