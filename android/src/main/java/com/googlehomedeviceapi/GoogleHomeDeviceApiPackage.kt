package com.googlehomedeviceapi

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class GoogleHomeDeviceApiPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == GoogleHomeDeviceApiModule.NAME) {
      GoogleHomeDeviceApiModule(reactContext)
    } else {
      null
    }
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      GoogleHomeDeviceApiModule.NAME to ReactModuleInfo(
        name = GoogleHomeDeviceApiModule.NAME,
        className = GoogleHomeDeviceApiModule.NAME,
        canOverrideExistingModule = false,
        needsEagerInit = false,
        isCxxModule = false,
        isTurboModule = true
      )
    )
  }
}
