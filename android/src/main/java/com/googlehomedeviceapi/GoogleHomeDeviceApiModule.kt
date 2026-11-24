package com.googlehomedeviceapi

import androidx.activity.ComponentActivity
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.google.home.*
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.annotation.HomeExperimentalGenericApi
import com.google.home.matter.serialization.ClusterBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

@ReactModule(name = GoogleHomeDeviceApiModule.NAME)
class GoogleHomeDeviceApiModule(reactContext: ReactApplicationContext) :
  NativeGoogleHomeDeviceApiSpec(reactContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val eventJobs = ConcurrentHashMap<String, Job>()
  private val attributeJobs = ConcurrentHashMap<String, Job>()

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
            val types = device.genericTypes().first()
            val typeIds = types.map { it.factory.factory.typeId.id }.distinct().sorted()
            val traitIds = types.flatMap { type ->
              type.genericTraits.map { it.factory.factory.traitId }
            }.distinct().sorted()
            val primaryType = types.firstOrNull { it.metadata.isPrimaryType }
              ?.factory?.factory?.typeId?.id
            val connectivity = device.sourceConnectivity.connectivityState
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
              putArray("traitIds", Arguments.createArray().apply {
                traitIds.forEach { pushString(it) }
              })
              putString("connectivity", connectivity.name)
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

  @OptIn(HomeExperimentalGenericApi::class)
  override fun executeTraitCommand(
    deviceId: String,
    deviceTypeId: String,
    partId: String,
    traitId: String,
    commandName: String,
    argumentsJson: String,
    useTimedCommand: Boolean,
    promise: Promise
  ) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val device = client.devices().first().firstOrNull { it.id.id == deviceId }
          ?: throw IllegalArgumentException("Device not found: $deviceId")
        val args = JSONObject(argumentsJson)
        val candidates = device.genericTypes().first().filter { type ->
          type.genericTraits.any { it.factory.factory.traitId == traitId }
        }
        val type = selectGenericType(candidates, deviceTypeId, partId)
        val trait = type.genericTraits.single { it.factory.factory.traitId == traitId }
        val descriptors = sdkCommands.filter { (id, descriptor) ->
          id == traitId &&
            (descriptor.commandName == commandName ||
              descriptor.commandName.removeSuffix("Command").replaceFirstChar { it.lowercase() } == commandName)
        }
        require(descriptors.size == 1) {
          if (descriptors.isEmpty()) "Unknown command: $traitId.$commandName"
          else "Ambiguous command: $traitId.$commandName"
        }
        val descriptor = descriptors.single().second
        val fields = jsonFields(args, descriptor.fields.values)
        val response = trait.sendCommand(descriptor, fields, useTimedCommand)
        promise.resolve(eventJsonValue(response).toString())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        promise.reject("COMMAND_ERROR", e.message, e)
      }
    }
  }

  @OptIn(HomeExperimentalGenericApi::class)
  private fun jsonFields(json: JSONObject, fields: Collection<Field>): Map<Field, Any?> {
    val byName = fields.associateBy { it.fieldName }
    val result = mutableMapOf<Field, Any?>()
    val names = json.keys()
    while (names.hasNext()) {
      val name = names.next()
      val field = byName[name] ?: throw IllegalArgumentException("Unknown command field: $name")
      result[field] = jsonFieldValue(field, json.get(name))
    }
    return result
  }

  @OptIn(HomeExperimentalGenericApi::class)
  private fun jsonFieldValue(field: Field, value: Any): Any {
    require(value != JSONObject.NULL) {
      "Null command field values are not supported by the SDK generic command serializer: ${field.fieldName}"
    }
    if (field.isList) {
      require(value is JSONArray) { "${field.fieldName} must be an array" }
      return (0 until value.length()).map { readScalar(field, value.get(it)) }
    }
    return readScalar(field, value)
  }

  @OptIn(HomeExperimentalGenericApi::class)
  internal fun readScalar(field: Field, value: Any): Any {
    require(value != JSONObject.NULL) { "Null is not supported in ${field.fieldName}" }
    fun integer(min: BigInteger, max: BigInteger): BigInteger {
      require(value is Number || value is String) { "${field.fieldName} must be an integer" }
      val number = try {
        BigDecimal(value.toString()).toBigIntegerExact()
      } catch (e: NumberFormatException) {
        throw IllegalArgumentException("${field.fieldName} must be an integer", e)
      } catch (e: ArithmeticException) {
        throw IllegalArgumentException("${field.fieldName} must be an integer", e)
      }
      require(number >= min && number <= max) { "${field.fieldName} is out of range" }
      if (value is Number) {
        require(number.abs() <= BigInteger("9007199254740991")) {
          "${field.fieldName} must be a string outside the JavaScript safe integer range"
        }
      }
      return number
    }
    return when (field.typeEnum) {
      Type.Byte -> integer(BigInteger.valueOf(Byte.MIN_VALUE.toLong()), BigInteger.valueOf(Byte.MAX_VALUE.toLong())).toByte()
      Type.Short -> integer(BigInteger.valueOf(Short.MIN_VALUE.toLong()), BigInteger.valueOf(Short.MAX_VALUE.toLong())).toShort()
      Type.Int -> integer(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
      Type.Long -> integer(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)).toLong()
      Type.UByte -> integer(BigInteger.ZERO, BigInteger.valueOf(255)).toInt().toUByte()
      Type.UShort -> integer(BigInteger.ZERO, BigInteger.valueOf(65535)).toInt().toUShort()
      Type.UInt -> integer(BigInteger.ZERO, BigInteger("4294967295")).toLong().toUInt()
      Type.ULong, Type.Bitmap -> integer(BigInteger.ZERO, BigInteger("18446744073709551615")).toString().toULong()
      Type.Enum -> {
        val descriptor = field.descriptor as? EnumDescriptor
          ?: throw IllegalArgumentException("Missing enum descriptor for ${field.fieldName}")
        if (value is String && !value.matches(Regex("[0-9]+"))) {
          descriptor.values.entries.singleOrNull { it.value.name == value }?.key
            ?: throw IllegalArgumentException("Unknown enum value for ${field.fieldName}: $value")
        } else {
          integer(BigInteger.ZERO, BigInteger("18446744073709551615")).toString().toULong()
        }
      }
      Type.Float -> {
        require(value is Number && value.toDouble().isFinite() && value.toFloat().isFinite()) {
          "${field.fieldName} must be a finite float"
        }
        value.toFloat()
      }
      Type.Double -> {
        require(value is Number && value.toDouble().isFinite()) { "${field.fieldName} must be a finite number" }
        value.toDouble()
      }
      Type.Boolean -> {
        require(value is Boolean) { "${field.fieldName} must be a boolean" }
        value
      }
      Type.String -> {
        require(value is String) { "${field.fieldName} must be a string" }
        value
      }
      Type.ByteArray -> {
        require(value is JSONArray) { "${field.fieldName} must be an array of bytes" }
        ByteArray(value.length()) { index ->
          val byte = value.get(index)
          require(byte is Number && byte.toDouble() == byte.toInt().toDouble() && byte.toInt() in 0..255) {
            "${field.fieldName} contains an invalid byte"
          }
          byte.toInt().toByte()
        }
      }
      Type.Struct -> {
        require(value is JSONObject) { "${field.fieldName} must be an object" }
        val descriptor = field.descriptor as? StructDescriptor
          ?: throw IllegalArgumentException("Missing struct descriptor for ${field.fieldName}")
        jsonFields(value, descriptor.fields.values)
      }
      else -> throw IllegalArgumentException("Unsupported field type for ${field.fieldName}: ${field.typeEnum}")
    }
  }

  override fun writeTraitAttribute(
    deviceId: String,
    deviceTypeId: String,
    partId: String,
    traitId: String,
    attributeName: String,
    valueJson: String,
    promise: Promise
  ) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val device = client.devices().first().firstOrNull { it.id.id == deviceId }
          ?: throw IllegalArgumentException("Device not found: $deviceId")
        val payload = JSONObject(valueJson)
        require(payload.has("value")) { "A value is required" }
        writeSdkAttribute(
          device.types().first(), deviceTypeId, partId, traitId, attributeName, payload.get("value")
        )
        promise.resolve(null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        promise.reject("ATTRIBUTE_WRITE_ERROR", e.message, e)
      }
    }
  }

  @OptIn(HomeExperimentalGenericApi::class)
  override fun readTraitAttribute(
    deviceId: String,
    deviceTypeId: String,
    partId: String,
    traitId: String,
    attributeName: String,
    promise: Promise
  ) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    scope.launch {
      try {
        val device = client.devices().first().firstOrNull { it.id.id == deviceId }
          ?: throw IllegalArgumentException("Device not found: $deviceId")
        val candidates = device.genericTypes().first().filter { type ->
          type.genericTraits.any { it.factory.factory.traitId == traitId }
        }
        val type = selectGenericType(candidates, deviceTypeId, partId)
        val trait = type.genericTraits.single { it.factory.factory.traitId == traitId }
        val entry = trait.attributes.entries.singleOrNull { it.key.fieldName == attributeName }
          ?: throw IllegalArgumentException("Attribute not reported: $traitId.$attributeName")
        promise.resolve(JSONObject().put("value", eventJsonValue(entry.value)).toString())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        promise.reject("ATTRIBUTE_READ_ERROR", e.message, e)
      }
    }
  }

  @OptIn(HomeExperimentalGenericApi::class, HomeExperimentalApi::class)
  override fun startDeviceEventMonitoring(
    subscriptionId: String,
    deviceId: String,
    deviceTypeId: String,
    partId: String,
    promise: Promise
  ) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    val job = scope.launch(start = CoroutineStart.LAZY) {
      var started = false
      try {
        val device = client.devices().first().firstOrNull { it.id.id == deviceId }
          ?: throw IllegalArgumentException("Device not found: $deviceId")
        val type = selectGenericType(device.genericTypes().first(), deviceTypeId, partId)
        val selectedTypeId = type.factory.factory.typeId.id
        started = true
        promise.resolve(null)
        type.genericEvents().collect { event ->
          val payload = eventJsonValue(event.eventFieldMap) as JSONObject
          emitOnDeviceEvent(Arguments.createMap().apply {
            putString("subscriptionId", subscriptionId)
            putString("deviceId", deviceId)
            putString("deviceTypeId", selectedTypeId)
            (event.partId ?: type.metadata.partId)?.let { putString("partId", it) }
            putString("eventId", event.eventId.id)
            putString("eventName", event.eventName)
            putString("eventNumber", event.eventNumber.toString())
            putString("payloadJson", payload.toString())
          })
        }
        throw IllegalStateException("Device event stream ended")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (started) {
          emitOnDeviceEventError(Arguments.createMap().apply {
            putString("subscriptionId", subscriptionId)
            putString("message", e.message ?: e.toString())
          })
        } else {
          promise.reject("EVENT_SUBSCRIPTION_ERROR", e.message, e)
        }
      } finally {
        coroutineContext[Job]?.let { eventJobs.remove(subscriptionId, it) }
      }
    }
    if (eventJobs.putIfAbsent(subscriptionId, job) != null) {
      job.cancel()
      promise.reject("DUPLICATE_SUBSCRIPTION", "Subscription already exists: $subscriptionId")
      return
    }
    job.start()
  }

  override fun stopDeviceEventMonitoring(subscriptionId: String) {
    eventJobs.remove(subscriptionId)?.cancel()
  }

  @OptIn(HomeExperimentalGenericApi::class)
  override fun startAttributeMonitoring(
    subscriptionId: String,
    deviceId: String,
    deviceTypeId: String,
    partId: String,
    traitId: String,
    attributeName: String,
    promise: Promise
  ) {
    val client = homeClient ?: return promise.reject("NOT_INIT", "Call initialize() first")
    val job = scope.launch(start = CoroutineStart.LAZY) {
      var started = false
      try {
        val device = client.devices().first().firstOrNull { it.id.id == deviceId }
          ?: throw IllegalArgumentException("Device not found: $deviceId")
        device.genericTypes().map { types ->
          val candidates = types.filter { type ->
            type.genericTraits.any { it.factory.factory.traitId == traitId }
          }
          val type = selectGenericType(candidates, deviceTypeId, partId)
          val trait = type.genericTraits.single { it.factory.factory.traitId == traitId }
          val entry = trait.attributes.entries.singleOrNull { it.key.fieldName == attributeName }
            ?: throw IllegalArgumentException("Attribute not reported: $traitId.$attributeName")
          JSONObject().put("value", eventJsonValue(entry.value)).toString()
        }.distinctUntilChanged().collect { value ->
          if (!started) {
            started = true
            promise.resolve(null)
          }
          emitOnAttributeChange(Arguments.createMap().apply {
            putString("subscriptionId", subscriptionId)
            putString("valueJson", value)
          })
        }
        throw IllegalStateException("Attribute stream ended")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (started) {
          emitOnAttributeMonitoringError(Arguments.createMap().apply {
            putString("subscriptionId", subscriptionId)
            putString("message", e.message ?: e.toString())
          })
        } else {
          promise.reject("ATTRIBUTE_SUBSCRIPTION_ERROR", e.message, e)
        }
      } finally {
        coroutineContext[Job]?.let { attributeJobs.remove(subscriptionId, it) }
      }
    }
    if (attributeJobs.putIfAbsent(subscriptionId, job) != null) {
      job.cancel()
      promise.reject("DUPLICATE_SUBSCRIPTION", "Subscription already exists: $subscriptionId")
      return
    }
    job.start()
  }

  override fun stopAttributeMonitoring(subscriptionId: String) {
    attributeJobs.remove(subscriptionId)?.cancel()
  }

  @OptIn(HomeExperimentalGenericApi::class)
  private fun selectGenericType(
    types: Collection<GenericDeviceType>,
    deviceTypeId: String,
    partId: String
  ): GenericDeviceType {
    val matches = types.filter {
      (deviceTypeId.isEmpty() || it.factory.factory.typeId.id == deviceTypeId) &&
        (partId.isEmpty() || it.metadata.partId == partId)
    }
    require(matches.size == 1) {
      if (matches.isEmpty()) "No matching device type" else "Device type is ambiguous; specify deviceTypeId and partId"
    }
    return matches.single()
  }

  @OptIn(HomeExperimentalGenericApi::class)
  private fun eventJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Boolean, is String, is Int, is Double -> value
    is Byte -> value.toInt()
    is Short -> value.toInt()
    is UByte -> value.toInt()
    is UShort -> value.toInt()
    is UInt -> value.toLong()
    is Long, is ULong -> value.toString()
    is Float -> value.toDouble()
    is Enum<*> -> value.name
    is ClusterBitmap -> value.toRaw().toString()
    is ByteArray -> JSONArray().apply { value.forEach { put(it.toInt() and 0xff) } }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(eventJsonValue(it)) } }
    is Map<*, *> -> JSONObject().apply {
      value.forEach { (key, item) ->
        val name = when (key) {
          is Field -> key.fieldName
          is String -> key
          else -> throw IllegalArgumentException("Unsupported payload field: $key")
        }
        put(name, eventJsonValue(item))
      }
    }
    else -> throw IllegalArgumentException("Unsupported payload value: ${value.javaClass.name}")
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
            types = SdkFactories.types,
            traits = SdkFactories.traits
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
