import GoogleHomeDeviceApi from './NativeGoogleHomeDeviceApi';
import type { MonitoringError, OnOffChange } from './NativeGoogleHomeDeviceApi';

let listenerCount = 0;
let monitoring = false;

export function subscribeToOnOffChanges(
  onChange: (event: OnOffChange) => void,
  onError: (error: MonitoringError) => void
): { remove(): void } {
  const changeSubscription = GoogleHomeDeviceApi.onOnOffChange(onChange);
  let errorSubscription: ReturnType<
    typeof GoogleHomeDeviceApi.onMonitoringError
  >;
  try {
    errorSubscription = GoogleHomeDeviceApi.onMonitoringError((error) => {
      monitoring = false;
      onError(error);
    });
  } catch (error) {
    changeSubscription.remove();
    throw error;
  }
  try {
    if (!monitoring) {
      monitoring = true;
      GoogleHomeDeviceApi.startOnOffMonitoring();
    }
    listenerCount++;
  } catch (error) {
    monitoring = false;
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
      listenerCount--;
      if (listenerCount === 0) {
        monitoring = false;
        GoogleHomeDeviceApi.stopOnOffMonitoring();
      }
    },
  };
}

export * from './NativeGoogleHomeDeviceApi';
export default GoogleHomeDeviceApi;
