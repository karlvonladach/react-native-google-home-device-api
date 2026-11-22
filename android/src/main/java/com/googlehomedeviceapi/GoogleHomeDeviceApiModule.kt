package com.googlehomedeviceapi

import androidx.activity.ComponentActivity
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.google.home.*
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@ReactModule(name = GoogleHomeDeviceApiModule.NAME)
class GoogleHomeDeviceApiModule(reactContext: ReactApplicationContext) :
  NativeGoogleHomeDeviceApiSpec(reactContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

  override fun getDevices(promise: Promise) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val structures = client.structures().first()
        val result = Arguments.createArray()
        for (structure in structures) {
          val devices = structure.devices().first()
          for (device in devices) {
            val map = Arguments.createMap().apply {
              putString("id", device.id.id)
              putString("name", device.name)
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
        val onOffTrait = device.type(OnOffLightDevice).trait(OnOff).first()
          ?: device.type(OnOffPluginUnitDevice).trait(OnOff).first()

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
