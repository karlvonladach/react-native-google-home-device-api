import { beforeEach, expect, it, jest } from '@jest/globals';
import NativeGoogleHomeDeviceApi from '../NativeGoogleHomeDeviceApi';
import { subscribeToOnOffChanges } from '../index';

jest.mock('../NativeGoogleHomeDeviceApi', () => ({
  __esModule: true,
  default: {
    onOnOffChange: jest.fn(),
    onMonitoringError: jest.fn(),
    startOnOffMonitoring: jest.fn(),
    stopOnOffMonitoring: jest.fn(),
  },
}));

const native = jest.mocked(NativeGoogleHomeDeviceApi);

beforeEach(() => {
  jest.clearAllMocks();
});

it('shares monitoring between listeners and stops after the last removal', () => {
  const removeChange = jest.fn();
  const removeError = jest.fn();
  native.onOnOffChange.mockReturnValue({ remove: removeChange });
  native.onMonitoringError.mockReturnValue({ remove: removeError });
  const onChange = jest.fn();
  const onError = jest.fn();
  const first = subscribeToOnOffChanges(onChange, onError);
  const second = subscribeToOnOffChanges(jest.fn(), jest.fn());

  expect(native.startOnOffMonitoring).toHaveBeenCalledTimes(1);
  native.onOnOffChange.mock.calls[0]![0]({ deviceId: 'lamp', isOn: true });
  expect(onChange).toHaveBeenCalledWith({ deviceId: 'lamp', isOn: true });
  first.remove();
  first.remove();
  expect(native.stopOnOffMonitoring).not.toHaveBeenCalled();
  second.remove();
  expect(native.stopOnOffMonitoring).toHaveBeenCalledTimes(1);
  expect(removeChange).toHaveBeenCalledTimes(2);
  expect(removeError).toHaveBeenCalledTimes(2);
});

it('reports monitoring failures and allows a new listener to retry', () => {
  native.onOnOffChange.mockReturnValue({ remove: jest.fn() });
  native.onMonitoringError.mockReturnValue({ remove: jest.fn() });
  const onError = jest.fn();
  const first = subscribeToOnOffChanges(jest.fn(), onError);
  native.onMonitoringError.mock.calls[0]![0]({ message: 'Disconnected' });
  expect(onError).toHaveBeenCalledWith({ message: 'Disconnected' });
  const second = subscribeToOnOffChanges(jest.fn(), jest.fn());
  expect(native.startOnOffMonitoring).toHaveBeenCalledTimes(2);
  first.remove();
  second.remove();
});

it('cleans up listeners if monitoring cannot be started', () => {
  const removeChange = jest.fn();
  const removeError = jest.fn();
  native.onOnOffChange.mockReturnValue({ remove: removeChange });
  native.onMonitoringError.mockReturnValue({ remove: removeError });
  native.startOnOffMonitoring.mockImplementationOnce(() => {
    throw new Error('Failed to start');
  });
  expect(() => subscribeToOnOffChanges(jest.fn(), jest.fn())).toThrow(
    'Failed to start'
  );
  expect(removeChange).toHaveBeenCalledTimes(1);
  expect(removeError).toHaveBeenCalledTimes(1);
});
