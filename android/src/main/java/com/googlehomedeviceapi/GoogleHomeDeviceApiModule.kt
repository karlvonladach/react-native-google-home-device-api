package com.googlehomedeviceapi

import androidx.activity.ComponentActivity
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.google.home.*
import com.google.home.annotation.HomeExperimentalGenericApi
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@ReactModule(name = GoogleHomeDeviceApiModule.NAME)
class GoogleHomeDeviceApiModule(reactContext: ReactApplicationContext) :
  NativeGoogleHomeDeviceApiSpec(reactContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var monitoringJob: Job? = null

  override fun getName(): String = NAME

  override fun initialize(promise: Promise) {
    try {
      val client = getOrCreateClient(reactApplicationContext)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("INIT_ERROR", e.message, e)
    }
  }

  override fun requestPermissions(promise: Promise) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    val activity = reactApplicationContext.currentActivity as? ComponentActivity
      ?: return promise.reject("NO_ACTIVITY", "Current activity is not a ComponentActivity")

    if (!isCallerRegistered) {
      promise.reject(
        "NOT_REGISTERED",
        "Call GoogleHomeDeviceApiModule.onActivityCreate(this) inside MainActivity.onCreate()"
      )
      return
    }

    scope.launch {
      try {
        // Check if permissions are already granted before launching UI
        val currentState = client.hasPermissions().first()
        if (currentState == PermissionsState.GRANTED) {
          promise.resolve(true)
          return@launch
        }

        // Launch the Google Home permissions consent sheet on the UI thread
        withContext(Dispatchers.Main) {
          val result = client.requestPermissions(forcePermissionFlow = ForcePermissionFlow.DO_NOT_FORCE_LAUNCH)
          promise.resolve(result.status == PermissionsResultStatus.SUCCESS)
        }

      } catch (e: Exception) {
        promise.reject("PERM_ERROR", e.message, e)
      }
    }
  }

  @OptIn(HomeExperimentalGenericApi::class)
  override fun getDevices(promise: Promise) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val structures = client.structures().first()
        val result = Arguments.createArray()
        for (structure in structures) {
          val devices = structure.devices().first()
          for (device in devices) {
            val onOffTrait = findOnOffTrait(device)
            val types = device.genericTypes().first()
            val typeIds = types.map { it.factory.factory.typeId.id }.distinct().sorted()
            val primaryType = types.firstOrNull { it.metadata.isPrimaryType }
              ?.factory?.factory?.typeId?.id
            val connectivity = device.sourceConnectivity.connectivityState
            val traitConnectivity = onOffTrait?.metadata?.sourceConnectivity?.connectivityState
            val map = Arguments.createMap().apply {
              putString("id", device.id.id)
              putString("name", device.name)
              primaryType?.let { putString("deviceType", it) }
              primaryType?.let { deviceTypeNames[it] }?.let { putString("deviceTypeName", it) }
              if (typeIds.size > 1) {
                putArray("deviceTypes", Arguments.createArray().apply {
                  typeIds.forEach { pushString(it) }
                })
              }
              putString("connectivity", connectivity.name)
              putBoolean(
                "isControllable",
                (connectivity == ConnectivityState.ONLINE || connectivity == ConnectivityState.PARTIALLY_ONLINE) &&
                  (traitConnectivity == ConnectivityState.ONLINE || traitConnectivity == ConnectivityState.PARTIALLY_ONLINE)
              )
              onOffTrait?.onOff?.let { putBoolean("isOn", it) }
            }
            result.pushMap(map)
          }
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("FETCH_ERROR", e.message, e)
      }
    }
  }

  override fun setOnOff(deviceId: String, turnOn: Boolean, promise: Promise) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val device = client.devices().first().first { it.id.id == deviceId }
        val onOffTrait = findOnOffTrait(device)

        if (onOffTrait == null) {
          promise.reject("NO_TRAIT", "Device does not support OnOff trait")
          return@launch
        }

        if (turnOn) onOffTrait.on() else onOffTrait.off()
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("CMD_ERROR", e.message, e)
      }
    }
  }

  private suspend fun findOnOffTrait(device: HomeDevice): OnOff? {
    val types = device.types().first()
    return types.filterIsInstance<OnOffLightDevice>().firstOrNull()?.trait(OnOff)
      ?: types.filterIsInstance<OnOffPluginUnitDevice>().firstOrNull()?.trait(OnOff)
  }

  override fun startOnOffMonitoring() {
    val client = homeClient
    if (client == null) {
      emitOnMonitoringError(Arguments.createMap().apply {
        putString("message", "Call initialize() first")
      })
      return
    }
    if (monitoringJob?.isActive == true) return
    monitoringJob = scope.launch {
      try {
        client.devices().collectLatest { devices ->
          coroutineScope {
            devices.forEach { device ->
              when {
                device.has(OnOffLightDevice) -> launch {
                  device.type(OnOffLightDevice)
                    .map { it.trait(OnOff)?.onOff }
                    .distinctUntilChanged()
                    .collect { state -> emitOnOffState(device.id.id, state) }
                }
                device.has(OnOffPluginUnitDevice) -> launch {
                  device.type(OnOffPluginUnitDevice)
                    .map { it.trait(OnOff)?.onOff }
                    .distinctUntilChanged()
                    .collect { state -> emitOnOffState(device.id.id, state) }
                }
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        emitOnMonitoringError(Arguments.createMap().apply {
          putString("message", e.message ?: e.toString())
        })
      }
    }
  }

  private fun emitOnOffState(deviceId: String, state: Boolean?) {
    if (state == null) return
    emitOnOnOffChange(Arguments.createMap().apply {
      putString("deviceId", deviceId)
      putBoolean("isOn", state)
    })
  }

  override fun stopOnOffMonitoring() {
    monitoringJob?.cancel()
    monitoringJob = null
  }

  override fun invalidate() {
    super.invalidate()
    scope.cancel()
  }

  companion object {
    const val NAME = "GoogleHomeDeviceApi"

    @Volatile
    private var homeClient: HomeClient? = null

    @Volatile
    private var isCallerRegistered = false

    private fun getOrCreateClient(context: android.content.Context): HomeClient {
      return homeClient ?: synchronized(this) {
        homeClient ?: run {
          val registry = FactoryRegistry(
            types = listOf(OnOffLightDevice, OnOffPluginUnitDevice),
            traits = listOf(OnOff)
          )
          val config = HomeConfig(
            coroutineContext = Dispatchers.IO,
            factoryRegistry = registry
          )
          Home.getClient(context.applicationContext, config).also {
            homeClient = it
          }
        }
      }
    }

    /**
     * Must be called inside MainActivity.onCreate() before the Activity starts.
     */
    @JvmStatic
    fun onActivityCreate(activity: ComponentActivity) {
      val client = getOrCreateClient(activity)
      client.registerActivityResultCallerForPermissions(activity)
      isCallerRegistered = true
    }

    @JvmStatic
    fun onActivityDestroy(activity: ComponentActivity) {
      if (isCallerRegistered) {
        isCallerRegistered = false
      }
    }
  }
}
