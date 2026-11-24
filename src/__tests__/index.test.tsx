import { beforeEach, expect, it, jest } from '@jest/globals';
import NativeGoogleHomeDeviceApi from '../NativeGoogleHomeDeviceApi';
import {
  deviceHasTrait,
  executeTraitCommand,
  getDevice,
  readTraitAttribute,
  subscribeToDeviceEvents,
  subscribeToTraitAttribute,
  TraitIds,
  writeTraitAttribute,
} from '../index';

jest.mock('../NativeGoogleHomeDeviceApi', () => ({
  __esModule: true,
  default: {
    readTraitAttribute: jest.fn(),
    executeTraitCommand: jest.fn(),
    writeTraitAttribute: jest.fn(),
    startDeviceEventMonitoring: jest.fn(),
    stopDeviceEventMonitoring: jest.fn(),
    onDeviceEvent: jest.fn(),
    onDeviceEventError: jest.fn(),
    startAttributeMonitoring: jest.fn(),
    stopAttributeMonitoring: jest.fn(),
    onAttributeChange: jest.fn(),
    onAttributeMonitoringError: jest.fn(),
  },
}));

const native = jest.mocked(NativeGoogleHomeDeviceApi);

beforeEach(() => {
  jest.clearAllMocks();
});

it('checks reported trait identifiers independently of device type', () => {
  const device = {
    id: 'device',
    name: 'Device',
    deviceType: 'other-type',
    traitIds: [TraitIds.OnOffTrait, TraitIds.ThermostatTrait],
  };
  expect(deviceHasTrait(device, TraitIds.OnOffTrait)).toBe(true);
  expect(deviceHasTrait(device, TraitIds.ThermostatTrait)).toBe(true);
  expect(deviceHasTrait(device, TraitIds.LevelControlTrait)).toBe(false);
  expect(deviceHasTrait({ ...device, traitIds: [] }, TraitIds.OnOffTrait)).toBe(
    false
  );
});

it('routes generic attribute changes and errors and cleans up', async () => {
  const removeChange = jest.fn();
  const removeError = jest.fn();
  native.onAttributeChange.mockReturnValue({ remove: removeChange });
  native.onAttributeMonitoringError.mockReturnValue({ remove: removeError });
  native.startAttributeMonitoring.mockResolvedValue();
  const onChange = jest.fn();
  const onError = jest.fn();
  const subscription = await getDevice('lamp', {
    partId: 'part',
  }).subscribeToAttribute('trait', 'state', onChange, onError);
  const id = native.startAttributeMonitoring.mock.calls[0]![0];
  expect(native.startAttributeMonitoring).toHaveBeenCalledWith(
    id,
    'lamp',
    '',
    'part',
    'trait',
    'state'
  );
  const emit = native.onAttributeChange.mock.calls[0]![0];
  emit({ subscriptionId: 'other', valueJson: '{"value":false}' });
  expect(onChange).not.toHaveBeenCalled();
  emit({ subscriptionId: id, valueJson: '{"value":null}' });
  expect(onChange).toHaveBeenCalledWith(null);
  emit({ subscriptionId: id, valueJson: '{}' });
  expect(onError).toHaveBeenCalledWith({
    message: expect.stringContaining('Invalid attribute payload'),
  });
  native.onAttributeMonitoringError.mock.calls[0]![0]({
    subscriptionId: id,
    message: 'Disconnected',
  });
  expect(onError).toHaveBeenCalledWith({ message: 'Disconnected' });
  subscription.remove();
  subscription.remove();
  expect(native.stopAttributeMonitoring).toHaveBeenCalledWith(id);
  expect(native.stopAttributeMonitoring).toHaveBeenCalledTimes(1);
  expect(removeChange).toHaveBeenCalledTimes(1);
  expect(removeError).toHaveBeenCalledTimes(1);
});

it('cleans up attribute listeners if subscription fails', async () => {
  const removeChange = jest.fn();
  const removeError = jest.fn();
  native.onAttributeChange.mockReturnValue({ remove: removeChange });
  native.onAttributeMonitoringError.mockReturnValue({ remove: removeError });
  native.startAttributeMonitoring.mockRejectedValueOnce(
    new Error('Unsupported')
  );
  await expect(
    subscribeToTraitAttribute('lamp', 'trait', 'state', jest.fn(), jest.fn())
  ).rejects.toThrow('Unsupported');
  expect(removeChange).toHaveBeenCalledTimes(1);
  expect(removeError).toHaveBeenCalledTimes(1);
});
it('reads an attribute with an explicit type and preserves null', async () => {
  native.readTraitAttribute.mockResolvedValue('{"value":null}');
  await expect(
    readTraitAttribute('lamp', 'trait-id', 'state', {
      deviceTypeId: 'type-id',
      partId: 'part-id',
    })
  ).resolves.toBeNull();
  expect(native.readTraitAttribute).toHaveBeenCalledWith(
    'lamp',
    'type-id',
    'part-id',
    'trait-id',
    'state'
  );
});

it('uses named Matter and Google traits with the generic APIs', async () => {
  native.readTraitAttribute.mockResolvedValue('{"value":true}');
  native.executeTraitCommand.mockResolvedValue('null');
  native.writeTraitAttribute.mockResolvedValue();
  const device = getDevice('lamp');

  await device.readAttribute(TraitIds.OnOffTrait, 'onOff');
  expect(native.readTraitAttribute).toHaveBeenCalledWith(
    'lamp',
    '',
    '',
    'home.matter.0000.clusters.0006',
    'onOff'
  );
  await device.executeCommand(TraitIds.OnOffTrait, 'on');
  expect(native.executeTraitCommand).toHaveBeenCalledWith(
    'lamp',
    '',
    '',
    'home.matter.0000.clusters.0006',
    'on',
    '{}',
    false
  );
  await device.writeAttribute(
    TraitIds.ThermostatTrait,
    'occupiedHeatingSetpoint',
    2100
  );
  expect(native.writeTraitAttribute).toHaveBeenCalledWith(
    'lamp',
    '',
    '',
    'home.matter.0000.clusters.0201',
    'occupiedHeatingSetpoint',
    '{"value":2100}'
  );
  expect(TraitIds.ChimeTrait).toBe('home.matter.0000.clusters.0556');
  expect(TraitIds.GoogleChimeTrait).toBe('home.matter.6006.clusters.fc6a');
});

it('routes device events and errors to the matching subscription and removes it', async () => {
  const removeEvent = jest.fn();
  const removeError = jest.fn();
  native.onDeviceEvent.mockReturnValue({ remove: removeEvent });
  native.onDeviceEventError.mockReturnValue({ remove: removeError });
  native.startDeviceEventMonitoring.mockResolvedValue();
  const onEvent = jest.fn();
  const onError = jest.fn();
  const subscription = await subscribeToDeviceEvents('lamp', onEvent, onError, {
    deviceTypeId: 'light',
  });
  const id = native.startDeviceEventMonitoring.mock.calls[0]![0];
  const emit = native.onDeviceEvent.mock.calls[0]![0];
  const event = {
    subscriptionId: id,
    deviceId: 'lamp',
    deviceTypeId: 'light',
    eventId: 'event-id',
    eventName: 'pressed',
    eventNumber: '9007199254740993',
    payloadJson: '{"count":2}',
  };
  emit({ ...event, subscriptionId: 'other' });
  expect(onEvent).not.toHaveBeenCalled();
  emit(event);
  expect(onEvent).toHaveBeenCalledWith({
    deviceId: 'lamp',
    deviceTypeId: 'light',
    partId: undefined,
    eventId: 'event-id',
    eventName: 'pressed',
    eventNumber: '9007199254740993',
    payload: { count: 2 },
  });
  native.onDeviceEventError.mock.calls[0]![0]({
    subscriptionId: id,
    message: 'Disconnected',
  });
  expect(onError).toHaveBeenCalledWith({ message: 'Disconnected' });
  subscription.remove();
  subscription.remove();
  expect(native.stopDeviceEventMonitoring).toHaveBeenCalledTimes(1);
  expect(native.stopDeviceEventMonitoring).toHaveBeenCalledWith(id);
  expect(removeEvent).toHaveBeenCalledTimes(1);
  expect(removeError).toHaveBeenCalledTimes(1);
});

it('cleans up event listeners when native subscription fails', async () => {
  const removeEvent = jest.fn();
  const removeError = jest.fn();
  native.onDeviceEvent.mockReturnValue({ remove: removeEvent });
  native.onDeviceEventError.mockReturnValue({ remove: removeError });
  native.startDeviceEventMonitoring.mockRejectedValueOnce(
    new Error('Ambiguous device type')
  );
  await expect(
    subscribeToDeviceEvents('lamp', jest.fn(), jest.fn())
  ).rejects.toThrow('Ambiguous device type');
  expect(removeEvent).toHaveBeenCalledTimes(1);
  expect(removeError).toHaveBeenCalledTimes(1);
});

it('forwards command fields and timed option and returns the response', async () => {
  native.executeTraitCommand.mockResolvedValue('{"position":42}');
  const device = getDevice('shade', { partId: 'part' });
  await expect(
    device.executeCommand(
      'trait-id',
      'move',
      { position: 42, nested: { value: '9007199254740993' } },
      { useTimedCommand: true }
    )
  ).resolves.toEqual({ position: 42 });
  expect(native.executeTraitCommand).toHaveBeenCalledWith(
    'shade',
    '',
    'part',
    'trait-id',
    'move',
    '{"position":42,"nested":{"value":"9007199254740993"}}',
    true
  );
});

it('rejects invalid command arguments before invoking native code', async () => {
  await expect(
    executeTraitCommand('device', 'trait', 'command', {}, { value: undefined })
  ).rejects.toThrow('invalid JSON value');
  expect(native.executeTraitCommand).not.toHaveBeenCalled();
});

it('forwards structured attribute writes and preserves explicit nulls', async () => {
  native.writeTraitAttribute.mockResolvedValue();
  await writeTraitAttribute(
    'thermostat',
    'trait-id',
    'presets',
    [{ name: null, fields: [1, 2] }],
    { deviceTypeId: 'type-id', partId: 'part-id' }
  );
  expect(native.writeTraitAttribute).toHaveBeenCalledWith(
    'thermostat',
    'type-id',
    'part-id',
    'trait-id',
    'presets',
    '{"value":[{"name":null,"fields":[1,2]}]}'
  );
});

it('propagates rejected attribute writes', async () => {
  native.writeTraitAttribute.mockRejectedValueOnce(new Error('Unsupported'));
  await expect(
    writeTraitAttribute('device', 'trait', 'attribute', 1)
  ).rejects.toThrow('Unsupported');
});
