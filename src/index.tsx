import GoogleHomeDeviceApi from './NativeGoogleHomeDeviceApi';
import type { HomeDevice, MonitoringError } from './NativeGoogleHomeDeviceApi';

export { TraitIds } from './TraitIds';
export type { TraitId } from './TraitIds';

let nextEventSubscriptionId = 0;
let nextAttributeSubscriptionId = 0;

export type DeviceTypeSelector = {
  deviceTypeId?: string;
  partId?: string;
};

export type CommandOptions = DeviceTypeSelector & {
  useTimedCommand?: boolean;
};

export type DeviceEvent = {
  deviceId: string;
  deviceTypeId: string;
  partId?: string;
  eventId: string;
  eventName: string;
  eventNumber: string;
  payload: unknown;
};

export function deviceHasTrait(device: HomeDevice, traitId: string): boolean {
  return device.traitIds.includes(traitId);
}

export async function executeTraitCommand(
  deviceId: string,
  traitId: string,
  commandName: string,
  options: CommandOptions = {},
  args: Record<string, unknown> = {}
): Promise<unknown> {
  const json = serializeJson(args);
  const result = await GoogleHomeDeviceApi.executeTraitCommand(
    deviceId,
    options.deviceTypeId ?? '',
    options.partId ?? '',
    traitId,
    commandName,
    json,
    options.useTimedCommand ?? false
  );
  return JSON.parse(result) as unknown;
}

function serializeJson(value: unknown): string {
  const json = JSON.stringify(value, (_key, item: unknown) => {
    if (
      item === undefined ||
      typeof item === 'function' ||
      typeof item === 'symbol' ||
      (typeof item === 'number' && !Number.isFinite(item))
    ) {
      throw new Error('Value contains an invalid JSON value');
    }
    return item;
  });
  if (json === undefined) throw new Error('Value is not JSON serializable');
  return json;
}

export async function writeTraitAttribute(
  deviceId: string,
  traitId: string,
  attributeName: string,
  value: unknown,
  selector: DeviceTypeSelector = {}
): Promise<void> {
  if (value === undefined) throw new Error('Attribute value is required');
  const json = serializeJson({ value });
  await GoogleHomeDeviceApi.writeTraitAttribute(
    deviceId,
    selector.deviceTypeId ?? '',
    selector.partId ?? '',
    traitId,
    attributeName,
    json
  );
}

export async function readTraitAttribute(
  deviceId: string,
  traitId: string,
  attributeName: string,
  selector: DeviceTypeSelector = {}
): Promise<unknown> {
  const json = await GoogleHomeDeviceApi.readTraitAttribute(
    deviceId,
    selector.deviceTypeId ?? '',
    selector.partId ?? '',
    traitId,
    attributeName
  );
  const result: unknown = JSON.parse(json);
  if (typeof result !== 'object' || result === null || !('value' in result)) {
    throw new Error('Invalid attribute response');
  }
  return result.value;
}

export async function subscribeToTraitAttribute(
  deviceId: string,
  traitId: string,
  attributeName: string,
  onChange: (value: unknown) => void,
  onError: (error: MonitoringError) => void,
  selector: DeviceTypeSelector = {}
): Promise<{ remove(): void }> {
  const subscriptionId = `attribute-${++nextAttributeSubscriptionId}`;
  const changeSubscription = GoogleHomeDeviceApi.onAttributeChange((event) => {
    if (event.subscriptionId !== subscriptionId) return;
    let value: unknown;
    try {
      const result: unknown = JSON.parse(event.valueJson);
      if (
        typeof result !== 'object' ||
        result === null ||
        !('value' in result)
      ) {
        throw new Error('Invalid attribute response');
      }
      value = result.value;
    } catch (error) {
      onError({ message: `Invalid attribute payload: ${String(error)}` });
      return;
    }
    onChange(value);
  });
  let errorSubscription: ReturnType<
    typeof GoogleHomeDeviceApi.onAttributeMonitoringError
  >;
  try {
    errorSubscription = GoogleHomeDeviceApi.onAttributeMonitoringError(
      (error) => {
        if (error.subscriptionId === subscriptionId)
          onError({ message: error.message });
      }
    );
  } catch (error) {
    changeSubscription.remove();
    throw error;
  }
  try {
    await GoogleHomeDeviceApi.startAttributeMonitoring(
      subscriptionId,
      deviceId,
      selector.deviceTypeId ?? '',
      selector.partId ?? '',
      traitId,
      attributeName
    );
  } catch (error) {
    changeSubscription.remove();
    errorSubscription.remove();
    throw error;
  }
  let removed = false;
  return {
    remove() {
      if (removed) return;
      removed = true;
      changeSubscription.remove();
      errorSubscription.remove();
      GoogleHomeDeviceApi.stopAttributeMonitoring(subscriptionId);
    },
  };
}

export async function subscribeToDeviceEvents(
  deviceId: string,
  onEvent: (event: DeviceEvent) => void,
  onError: (error: MonitoringError) => void,
  selector: DeviceTypeSelector = {}
): Promise<{ remove(): void }> {
  const subscriptionId = `device-event-${++nextEventSubscriptionId}`;
  const eventSubscription = GoogleHomeDeviceApi.onDeviceEvent((event) => {
    if (event.subscriptionId !== subscriptionId) return;
    let payload: unknown;
    try {
      payload = JSON.parse(event.payloadJson);
    } catch (error) {
      onError({ message: `Invalid event payload: ${String(error)}` });
      return;
    }
    onEvent({
      deviceId: event.deviceId,
      deviceTypeId: event.deviceTypeId,
      partId: event.partId,
      eventId: event.eventId,
      eventName: event.eventName,
      eventNumber: event.eventNumber,
      payload,
    });
  });
  let errorSubscription: ReturnType<
    typeof GoogleHomeDeviceApi.onDeviceEventError
  >;
  try {
    errorSubscription = GoogleHomeDeviceApi.onDeviceEventError((error) => {
      if (error.subscriptionId === subscriptionId) {
        onError({ message: error.message });
      }
    });
  } catch (error) {
    eventSubscription.remove();
    throw error;
  }
  try {
    await GoogleHomeDeviceApi.startDeviceEventMonitoring(
      subscriptionId,
      deviceId,
      selector.deviceTypeId ?? '',
      selector.partId ?? ''
    );
  } catch (error) {
    eventSubscription.remove();
    errorSubscription.remove();
    throw error;
  }

  let removed = false;
  return {
    remove() {
      if (removed) return;
      removed = true;
      eventSubscription.remove();
      errorSubscription.remove();
      GoogleHomeDeviceApi.stopDeviceEventMonitoring(subscriptionId);
    },
  };
}

export function getDevice(deviceId: string, selector: DeviceTypeSelector = {}) {
  const command = (
    traitId: string,
    commandName: string,
    args: Record<string, unknown> = {},
    options: { useTimedCommand?: boolean } = {}
  ) =>
    executeTraitCommand(
      deviceId,
      traitId,
      commandName,
      { ...selector, ...options },
      args
    );
  const read = (traitId: string, attributeName: string) =>
    readTraitAttribute(deviceId, traitId, attributeName, selector);
  const write = (traitId: string, attributeName: string, value: unknown) =>
    writeTraitAttribute(deviceId, traitId, attributeName, value, selector);
  return {
    executeCommand: command,
    readAttribute: read,
    writeAttribute: write,
    subscribeToAttribute: (
      traitId: string,
      attributeName: string,
      onChange: (value: unknown) => void,
      onError: (error: MonitoringError) => void
    ) =>
      subscribeToTraitAttribute(
        deviceId,
        traitId,
        attributeName,
        onChange,
        onError,
        selector
      ),
    subscribeToEvents: (
      onEvent: (event: DeviceEvent) => void,
      onError: (error: MonitoringError) => void
    ) => subscribeToDeviceEvents(deviceId, onEvent, onError, selector),
  };
}

export * from './NativeGoogleHomeDeviceApi';
export default GoogleHomeDeviceApi;
