package com.googlehomedeviceapi

import com.facebook.react.bridge.ReactApplicationContext

class GoogleHomeDeviceApiModule(reactContext: ReactApplicationContext) :
  NativeGoogleHomeDeviceApiSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeGoogleHomeDeviceApiSpec.NAME
  }
}
