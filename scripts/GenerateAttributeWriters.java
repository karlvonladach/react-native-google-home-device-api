import com.google.home.Field;
import com.google.home.EnumDescriptor;
import com.google.home.StructDescriptor;
import com.google.home.TraitFactory;
import com.google.home.matter.serialization.ClusterBitmap;
import com.google.home.matter.serialization.ClusterEnum;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.ZipFile;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.KType;
import kotlin.reflect.jvm.ReflectJvmMapping;

public class GenerateAttributeWriters {
  private static final Map<Class<?>, String> structs = new LinkedHashMap<>();
  private static final Queue<Class<?>> pending = new ArrayDeque<>();

  private static String qualified(Class<?> type) {
    return type.getName().replace('$', '.');
  }

  private static String kotlinName(KType type) {
    KClass<?> classifier = (KClass<?>) type.getClassifier();
    return classifier.getQualifiedName();
  }

  private static Class<?> javaType(KType type) {
    return JvmClassMappingKt.getJavaClass((KClass<?>) type.getClassifier());
  }

  private static KType argument(KType type) {
    KType result = type.getArguments().get(0).getType();
    if (result == null) throw new IllegalStateException("Star-projected type: " + type);
    return result;
  }

  private static void verifyField(KType type, Field field) {
    String name = kotlinName(type);
    if (name.equals("kotlin.collections.List") ||
        name.equals("com.google.home.matter.serialization.OptionalValue")) {
      verifyField(argument(type), field);
      return;
    }
    Class<?> cls = javaType(type);
    String expected;
    if (ClusterEnum.class.isAssignableFrom(cls)) expected = "Enum";
    else if (ClusterBitmap.class.isAssignableFrom(cls)) expected = "Bitmap";
    else if (com.google.home.ClusterStruct.class.isAssignableFrom(cls)) expected = "Struct";
    else if (name.startsWith("kotlin.")) expected = name.substring("kotlin.".length());
    else throw new IllegalStateException("Unsupported field type: " + type);
    if (!field.getTypeEnum().name().equals(expected)) {
      throw new IllegalStateException("Field type mismatch for " + field.getFieldName() +
        ": " + type + " versus " + field.getTypeEnum());
    }
    if (expected.equals("Enum") && !(field.getDescriptor() instanceof EnumDescriptor)) {
      throw new IllegalStateException("Missing enum descriptor for " + field.getFieldName());
    }
  }

  private static String decode(KType type, String field, String value) {
    if (type.isMarkedNullable()) {
      return "if (" + value + " == JSONObject.NULL) null else " + decodeNonNull(type, field, value);
    }
    return decodeNonNull(type, field, value);
  }

  private static String decodeNonNull(KType type, String field, String value) {
    String name = kotlinName(type);
    if (name.equals("kotlin.collections.List")) {
      return "readArray(" + value + ").map { item -> " + decode(argument(type), field, "item") + " }";
    }
    if (name.equals("com.google.home.matter.serialization.OptionalValue")) {
      KType inner = argument(type);
      return "OptionalValue." + (inner.isMarkedNullable() ? "ofNullable" : "present") +
        "(" + decode(inner, field, value) + ")";
    }
    Class<?> cls = javaType(type);
    if (ClusterEnum.class.isAssignableFrom(cls)) {
      return "readEnum(" + field + ", " + value + ", " + qualified(cls) + ".values())";
    }
    if (ClusterBitmap.class.isAssignableFrom(cls)) {
      return "readBitmap(" + field + ", " + value + ", " + qualified(cls) + ".Adapter)";
    }
    if (com.google.home.ClusterStruct.class.isAssignableFrom(cls)) {
      String function = structs.get(cls);
      if (function == null) {
        function = "readStruct" + structs.size();
        structs.put(cls, function);
        pending.add(cls);
      }
      return function + "(" + value + ")";
    }
    if (name.matches("kotlin\\.(Byte|Short|Int|Long|UByte|UShort|UInt|ULong|Float|Double|String|Boolean|ByteArray)")) {
      return "readScalar(" + field + ", " + value + ") as " + name;
    }
    throw new IllegalStateException("Unsupported writable type: " + type);
  }

  private static String structFunction(Class<?> cls, String function) throws Exception {
    Object adapter = cls.getField("Adapter").get(null);
    if (!(adapter instanceof StructDescriptor)) throw new IllegalStateException("No struct descriptor: " + cls);
    StructDescriptor descriptor = (StructDescriptor) adapter;
    KClass<?> kotlinClass = JvmClassMappingKt.getKotlinClass(cls);
    List<KFunction<?>> constructors = new ArrayList<>(kotlinClass.getConstructors());
    if (constructors.size() != 1) throw new IllegalStateException("Unexpected constructors: " + cls);
    KFunction<?> ctor = constructors.get(0);
    StringBuilder output = new StringBuilder();
    output.append("private fun GoogleHomeDeviceApiModule.").append(function).append("(input: Any): ")
      .append(qualified(cls)).append(" {\n");
    output.append("  val obj = input as? JSONObject ?: throw IllegalArgumentException(\"Expected object for ")
      .append(qualified(cls)).append("\")\n");
    output.append("  requireObjectKeys(obj, setOf(");
    List<KParameter> params = ctor.getParameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) output.append(", ");
      output.append('"').append(params.get(i).getName()).append('"');
    }
    output.append("))\n  return ").append(qualified(cls)).append("(\n");
    for (KParameter param : params) {
      String paramName = param.getName();
      List<Field> matches = descriptor.getFields().values().stream()
        .filter(field -> field.getFieldName().replace("`", "").equalsIgnoreCase(paramName)).toList();
      if (matches.size() != 1) throw new IllegalStateException("Missing or ambiguous struct field: " + cls + "." + paramName);
      verifyField(param.getType(), matches.get(0));
      String sdkName = matches.get(0).getFieldName();
      String field = "structField(" + qualified(cls) + ".Adapter, \"" + sdkName + "\")";
      output.append("    `").append(paramName).append("` = if (obj.has(\"").append(paramName)
        .append("\")) ").append(decode(param.getType(), field, "obj.get(\"" + paramName + "\")"))
        .append(" else ");
      if (kotlinName(param.getType()).equals("com.google.home.matter.serialization.OptionalValue")) {
        output.append("OptionalValue.absent()");
      } else {
        output.append("throw IllegalArgumentException(\"Missing struct field: ").append(paramName).append("\")");
      }
      output.append(",\n");
    }
    output.append("  )\n}\n\n");
    return output.toString();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected SDK types JAR and output path");
    StringBuilder body = new StringBuilder();
    StringBuilder traitFunctions = new StringBuilder();
    int traits = 0, attributes = 0;
    try (ZipFile zip = new ZipFile(args[0])) {
      List<String> entries = zip.stream().map(entry -> entry.getName())
        .filter(name -> name.matches("com/google/home/(google|matter/standard)/[^/$]+Trait\\$MutableAttributes\\.class"))
        .sorted().toList();
      for (String entry : entries) {
        String mutableName = entry.substring(0, entry.length() - 6).replace('/', '.');
        String traitName = mutableName.substring(0, mutableName.indexOf("Trait$MutableAttributes"));
        Class<?> traitClass = Class.forName(traitName);
        TraitFactory<?> factory = (TraitFactory<?>) traitClass.getField("Companion").get(null);
        Class<?> mutable = Class.forName(mutableName);
        Map<String, KFunction<?>> setters = new java.util.TreeMap<>();
        for (Method method : mutable.getDeclaredMethods()) {
          String methodName = method.getName();
          if (methodName.startsWith("set") && !methodName.startsWith("set_") &&
              !methodName.contains("$") &&
              !methodName.matches("set[A-Z][A-Za-z0-9_]*(?:-[A-Za-z0-9]+)?")) {
            throw new IllegalStateException("Unrecognized setter: " + method);
          }
          if (!methodName.matches("set[A-Z][A-Za-z0-9_]*(?:-[A-Za-z0-9]+)?") ||
              method.getParameterCount() != 1) continue;
          KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
          if (function == null) throw new IllegalStateException("Missing Kotlin metadata: " + method);
          String name = function.getName().substring(3);
          name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
          if (factory.getAttributeByName(name) == null || setters.put(name, function) != null)
            throw new IllegalStateException("Unknown or duplicate attribute: " + traitName + "." + name);
        }
        if (setters.isEmpty()) continue;
        String writer = "writeTrait" + traits++;
        body.append("    ").append(traitName).append(".factory.traitId -> ")
          .append(writer).append("(types, deviceTypeId, partId, traitId, attributeName, value)\n");
        traitFunctions.append("@OptIn(HomeExperimentalGenericApi::class)\n")
          .append("private suspend fun GoogleHomeDeviceApiModule.").append(writer)
          .append("(types: Collection<DeviceType>, deviceTypeId: String, partId: String, traitId: String, attributeName: String, value: Any) {\n")
          .append("  val trait = selectWritableTrait(types, deviceTypeId, partId, ").append(traitName).append(")\n")
          .append("  when (attributeName) {\n");
        for (var entrySet : setters.entrySet()) {
          String name = entrySet.getKey();
          KFunction<?> function = entrySet.getValue();
          KType type = function.getParameters().get(1).getType();
          verifyField(type, factory.getAttributeByName(name));
          traitFunctions.append("    \"").append(name).append("\" -> {\n")
            .append("      val field = ").append(traitName).append(".Attribute.`").append(name).append("`\n")
            .append("      require(trait.supports(field)) { \"Attribute is not supported by this device: $attributeName\" }\n")
            .append("      val converted = ").append(decode(type, "field", "value")).append("\n")
            .append("      trait.update { ").append(function.getName()).append("(converted) }\n")
            .append("    }\n");
          attributes++;
        }
        traitFunctions.append("    else -> throw IllegalArgumentException(\"Attribute is not writable: $traitId.$attributeName\")\n")
          .append("  }\n}\n\n");
      }
    }
    StringBuilder helpers = new StringBuilder();
    while (!pending.isEmpty()) {
      Class<?> cls = pending.remove();
      helpers.append(structFunction(cls, structs.get(cls)));
    }
    String output = "package com.googlehomedeviceapi\n\n" +
      "import com.google.home.*\n" +
      "import com.google.home.annotation.HomeExperimentalGenericApi\n" +
      "import com.google.home.matter.serialization.OptionalValue\n" +
      "import org.json.JSONObject\n\n" +
      "// Generated by scripts/GenerateAttributeWriters.java.\n" +
      "@OptIn(HomeExperimentalGenericApi::class)\n" +
      "internal suspend fun GoogleHomeDeviceApiModule.writeSdkAttribute(types: Collection<DeviceType>, deviceTypeId: String, partId: String, traitId: String, attributeName: String, value: Any) {\n" +
      "  when (traitId) {\n" + body +
      "    else -> throw IllegalArgumentException(\"Trait has no writable attributes: $traitId\")\n" +
      "  }\n}\n\n" + traitFunctions + helpers;
    Files.writeString(Path.of(args[1]), output);
    System.out.println("Generated " + attributes + " writers for " + traits + " traits and " + structs.size() + " structs");
  }
}
