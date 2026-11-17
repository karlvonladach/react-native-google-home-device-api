import GoogleHomeDeviceApi from './NativeGoogleHomeDeviceApi';

export function multiply(a: number, b: number): number {
  return GoogleHomeDeviceApi.multiply(a, b);
}
