package com.wormzjl.createcheme.science.material;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
class MaterialFluidDataTest {
 private static final String ID="createcheme:tjl20_methane",ROOT="data/createcheme/materials/transport/";
 @AfterEach void reset(){MaterialRuntime.reset();}
 @Test void fluidReferenceEditsInvalidateOnlyTheirConsumersAndCapturedModelsStayStable() throws Exception {
  var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"dissolved_viscosity.json";
  var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();var first=data.getAsJsonArray("curves").get(0).getAsJsonObject();
  var values=first.getAsJsonArray("viscosities_pascal_seconds");values.set(0,new JsonPrimitive(values.get(0).getAsDouble()*2));resources.put(path,data.toString());
  var changed=MaterialCatalog.parse(resources);var p=original.requirePackage(ID);var amounts=new double[p.components().size()];
  amounts[p.components().indexOf("Methane")]=.05;amounts[p.components().indexOf("crude_pc12")]=.95;
  var captured=new MixtureViscosity(original,ID);double before=captured.liquid(293.15,amounts).pascalSeconds();
  assertEquals(original.physicsFingerprint(ID,p.components()),changed.physicsFingerprint(ID,p.components()));
  assertEquals(original.fluidThermoFingerprint(ID),changed.fluidThermoFingerprint(ID));
  assertNotEquals(original.viscosityFingerprint(ID),changed.viscosityFingerprint(ID));
  var admitted=new CountDownLatch(1);var released=new CountDownLatch(1);
  try(var worker=Executors.newSingleThreadExecutor()) {
   var result=worker.submit(()->{admitted.countDown();if(!released.await(5,TimeUnit.SECONDS))throw new AssertionError("release");return captured.liquid(293.15,amounts).pascalSeconds();});
   assertTrue(admitted.await(5,TimeUnit.SECONDS));MaterialRuntime.publish(changed);released.countDown();
   assertEquals(before,result.get(5,TimeUnit.SECONDS));
  } finally {released.countDown();}
  assertEquals(Math.pow(2,.05),new MixtureViscosity(changed,ID).liquid(293.15,amounts).pascalSeconds()/before,1e-12);
  path=ROOT+"liquid_calibration.json";data=JsonParser.parseString(resources.get(path)).getAsJsonObject();
  first=data.getAsJsonArray("points").get(0).getAsJsonObject();first.addProperty("molarVolumeCubicMetres",first.get("molarVolumeCubicMetres").getAsDouble()*1.01);resources.put(path,data.toString());
  var recalibrated=MaterialCatalog.parse(resources);
  assertNotEquals(changed.fluidThermoFingerprint(ID),recalibrated.fluidThermoFingerprint(ID));
  assertEquals(changed.physicsFingerprint(ID,p.components()),recalibrated.physicsFingerprint(ID,p.components()));
 }
 @Test void malformedFluidRecordsFailBeforePublicationWithResourceAndFieldContext() {
  var resources=new HashMap<>(MaterialCatalog.bundled().resources());String path=ROOT+"dissolved_viscosity.json";
  var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();var first=data.getAsJsonArray("curves").get(0).getAsJsonObject();
  var temperatures=first.getAsJsonArray("temperatures_kelvin");temperatures.set(1,temperatures.get(0));resources.put(path,data.toString());
  var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));assertTrue(failure.getMessage().contains(path));assertTrue(failure.getMessage().contains("temperature"));
 }
}
