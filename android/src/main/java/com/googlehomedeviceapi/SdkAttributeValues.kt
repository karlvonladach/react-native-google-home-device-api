package com.googlehomedeviceapi

import com.google.home.DeviceType
import com.google.home.Field
import com.google.home.StructDescriptor
import com.google.home.Trait
import com.google.home.TraitFactory
import com.google.home.annotation.HomeExperimentalGenericApi
import com.google.home.matter.serialization.Bitmap
import com.google.home.matter.serialization.BitmapAdapter
import com.google.home.matter.serialization.ClusterBitmap
import com.google.home.matter.serialization.ClusterEnum
import org.json.JSONArray
import org.json.JSONObject

internal fun <T : Trait> selectWritableTrait(
  types: Collection<DeviceType>,
  deviceTypeId: String,
  partId: String,
  factory: TraitFactory<T>
): T {
  val matches = types.filter {
    (deviceTypeId.isEmpty() || it.factory.factory.typeId.id == deviceTypeId) &&
      (partId.isEmpty() || it.metadata.partId == partId)
  }.mapNotNull { it.trait(factory) }
  require(matches.size == 1) {
    if (matches.isEmpty()) "No matching trait" else "Device type is ambiguous; specify deviceTypeId and partId"
  }
  return matches.single()
}

internal fun readArray(value: Any): List<Any> {
  require(value is JSONArray) { "Expected an array" }
  return (0 until value.length()).map { value.get(it) }
}

internal fun requireObjectKeys(obj: JSONObject, allowed: Set<String>) {
  val keys = obj.keys()
  while (keys.hasNext()) {
    val key = keys.next()
    require(key in allowed) { "Unknown struct field: $key" }
  }
}

@OptIn(HomeExperimentalGenericApi::class)
internal fun structField(descriptor: StructDescriptor, name: String): Field =
  descriptor.fields.values.single { it.fieldName == name }

@OptIn(HomeExperimentalGenericApi::class)
internal fun <T> GoogleHomeDeviceApiModule.readEnum(field: Field, value: Any, entries: Array<T>): T
  where T : Enum<T>, T : ClusterEnum {
  val raw = readScalar(field, value) as ULong
  return entries.singleOrNull { it.value == raw && it.name != "UnknownValue" }
    ?: throw IllegalArgumentException("Invalid enum value for ${field.fieldName}: $value")
}

@OptIn(HomeExperimentalGenericApi::class)
internal fun <T : ClusterBitmap> GoogleHomeDeviceApiModule.readBitmap(
  field: Field,
  value: Any,
  adapter: BitmapAdapter<T>
): T {
  val raw = readScalar(field, value) as ULong
  val bitmap = adapter.toRuntime(Bitmap.toRuntime(raw))
  require(bitmap.toRaw() == raw) { "Unsupported bitmap bits for ${field.fieldName}" }
  return bitmap
}
