package com.googlehomedeviceapi;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.home.DeviceType;
import com.google.home.Updatable;
import com.google.home.matter.standard.LevelControl;
import com.google.home.matter.standard.LevelControlTrait;
import com.google.home.matter.standard.OnOff;
import com.google.home.matter.standard.OnOffTrait;
import com.google.home.matter.standard.Thermostat;
import com.google.home.matter.standard.ThermostatTrait;
import java.util.Collections;
import java.util.Arrays;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SdkAttributeWritersTest {
  private final GoogleHomeDeviceApiModule module =
    mock(GoogleHomeDeviceApiModule.class, CALLS_REAL_METHODS);
  private final Continuation<Unit> continuation = new Continuation<Unit>() {
    @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
    @Override public void resumeWith(Object result) { fail("Unexpected suspension"); }
  };

  private <M> void captureUpdate(Updatable<?, M> trait, M mutable) {
    doAnswer(invocation -> {
      Function1<M, Unit> update = invocation.getArgument(1);
      update.invoke(mutable);
      return null;
    }).when(trait).update(any(), any(), null);
  }

  private void write(DeviceType device, String traitId, String name, Object value) {
    assertEquals(Unit.INSTANCE, SdkAttributeWritersKt.writeSdkAttribute(
      module, Collections.singletonList(device), "", "", traitId, name, value, continuation
    ));
  }

  @Test public void writesAndValidatesUnsignedAttribute() throws Exception {
    OnOff trait = mock(OnOff.class);
    DeviceType device = mock(DeviceType.class);
    when(device.trait(OnOff.Companion)).thenReturn(trait);
    when(trait.supports(OnOff.Attribute.onTime)).thenReturn(true);
    when(trait.getFeatureMap()).thenReturn(new OnOffTrait.Feature());
    OnOffTrait.MutableAttributes mutable = new OnOffTrait.MutableAttributes(trait);
    captureUpdate(trait, mutable);
    String id = OnOff.Companion.getFactory().getTraitId();
    write(device, id, "onTime", 65535);
    Object result = mutable.getClass().getMethod("getOnTime-XRpZGF0").invoke(mutable);
    assertEquals("65535", result.toString());
    try {
      write(device, id, "onTime", 65536);
      fail("Expected range error");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("out of range"));
    }
    verify(trait, times(1)).update(any(), any(), null);
  }

  @Test public void writesBitmapAndRejectsUnknownBits() {
    LevelControl trait = mock(LevelControl.class);
    DeviceType device = mock(DeviceType.class);
    when(device.trait(LevelControl.Companion)).thenReturn(trait);
    when(trait.supports(LevelControl.Attribute.options)).thenReturn(true);
    when(trait.getFeatureMap()).thenReturn(new LevelControlTrait.Feature());
    LevelControlTrait.MutableAttributes mutable = new LevelControlTrait.MutableAttributes(trait);
    captureUpdate(trait, mutable);
    String id = LevelControl.Companion.getFactory().getTraitId();
    write(device, id, "options", 1);
    assertTrue(mutable.getOptions().getExecuteIfOff());
    try {
      write(device, id, "options", 128);
      fail("Expected unknown bitmap bits to be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("bitmap"));
    }
    verify(trait, times(1)).update(any(), any(), null);
  }

  @Test public void writesNullableEnumAndChecksSupport() {
    OnOff trait = mock(OnOff.class);
    DeviceType device = mock(DeviceType.class);
    when(device.trait(OnOff.Companion)).thenReturn(trait);
    when(trait.getFeatureMap()).thenReturn(new OnOffTrait.Feature());
    OnOffTrait.MutableAttributes mutable = new OnOffTrait.MutableAttributes(trait);
    captureUpdate(trait, mutable);
    String id = OnOff.Companion.getFactory().getTraitId();
    try {
      write(device, id, "startUpOnOff", "On");
      fail("Expected unsupported attribute to be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("not supported"));
    }
    when(trait.supports(OnOff.Attribute.startUpOnOff)).thenReturn(true);
    write(device, id, "startUpOnOff", "Toggle");
    assertEquals(OnOffTrait.StartUpOnOffEnum.Toggle, mutable.getStartUpOnOff());
    write(device, id, "startUpOnOff", JSONObject.NULL);
    assertNull(mutable.getStartUpOnOff());
    verify(trait, times(2)).update(any(), any(), null);
  }

  @Test public void rejectsAmbiguousDeviceTypes() {
    OnOff trait = mock(OnOff.class);
    DeviceType first = mock(DeviceType.class);
    DeviceType second = mock(DeviceType.class);
    when(first.trait(OnOff.Companion)).thenReturn(trait);
    when(second.trait(OnOff.Companion)).thenReturn(trait);
    try {
      SdkAttributeWritersKt.writeSdkAttribute(module, Arrays.asList(first, second),
        "", "", OnOff.Companion.getFactory().getTraitId(), "onTime", 1, continuation);
      fail("Expected ambiguous device type to be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("ambiguous"));
    }
    verify(trait, never()).update(any(), any(), null);
  }

  @Test public void writesStructListAndPreservesAbsentAndNullFields() throws Exception {
    Thermostat trait = mock(Thermostat.class);
    DeviceType device = mock(DeviceType.class);
    when(device.trait(Thermostat.Companion)).thenReturn(trait);
    when(trait.supports(Thermostat.Attribute.presets)).thenReturn(true);
    when(trait.getFeatureMap()).thenReturn(new ThermostatTrait.Feature());
    ThermostatTrait.MutableAttributes mutable = new ThermostatTrait.MutableAttributes(trait);
    captureUpdate(trait, mutable);
    JSONObject preset = new JSONObject()
      .put("presetHandle", JSONObject.NULL)
      .put("presetScenario", "Occupied")
      .put("name", JSONObject.NULL)
      .put("builtIn", JSONObject.NULL);
    String id = Thermostat.Companion.getFactory().getTraitId();
    write(device, id, "presets", new JSONArray().put(preset));
    ThermostatTrait.PresetStruct result = mutable.getPresets().get(0);
    assertEquals(ThermostatTrait.PresetScenarioEnum.Occupied, result.getPresetScenario());
    assertTrue(result.getName().isPresent());
    assertNull(result.getName().getValue());
    assertFalse(result.getCoolingSetpoint().isPresent());
    try {
      write(device, id, "presets", new JSONArray().put(new JSONObject().put("unexpected", 1)));
      fail("Expected unknown struct field to be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Unknown struct field"));
    }
  }
}
