package com.wormzjl.createcheme.science.material;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class MaterialAuthoringTest {
 private static final String ROOT="data/createcheme/materials/",ID="createcheme:tjl20_methane";
 @Test void physicsIdentityIgnoresAssaysButTracksActualSelectedProperties() {
  var c=MaterialCatalog.bundled();var axis=c.requirePackage(ID).components();
  assertEquals(c.physicsFingerprint(ID,axis),c.physicsFingerprint("createcheme:bonga_tjl20",axis));
  assertEquals(c.physicsFingerprint(ID,axis),c.physicsFingerprint("createcheme:tjl20_methane_nitrogen",axis));
  var resources=new HashMap<>(c.resources());var a=JsonParser.parseString(resources.get(ROOT+"assays/tjl20.json")).getAsJsonObject();
  a.getAsJsonObject("amounts_by_component").addProperty("crude_pc01",.8);resources.put(ROOT+"assays/tjl20.json",a.toString());
  var changed=MaterialCatalog.parse(resources);
  assertNotEquals(c.requirePackage(ID).fingerprint(),changed.requirePackage(ID).fingerprint());
  assertEquals(c.physicsFingerprint(ID,axis),changed.physicsFingerprint(ID,axis));
  var p=JsonParser.parseString(resources.get(ROOT+"properties/crude_pc08.json")).getAsJsonObject();
  p.addProperty("standard_liquid_density_kg_per_m3",913);resources.put(ROOT+"properties/crude_pc08.json",p.toString());
  changed=MaterialCatalog.parse(resources);
  assertNotEquals(c.physicsFingerprint(ID,axis),changed.physicsFingerprint(ID,axis));
  assertEquals(c.propertyPhysicsFingerprint(ID,"crude_pc07"),changed.propertyPhysicsFingerprint(ID,"crude_pc07"));
  assertNotEquals(c.propertyPhysicsFingerprint(ID,"crude_pc08"),changed.propertyPhysicsFingerprint(ID,"crude_pc08"));
 }
 @Test void basisCyclesAndUnusedBadReferencesFailWithResourceContext() {
  var records=new HashMap<>(MaterialCatalog.bundled().resources());String path=ROOT+"bases/bad.json";
  records.put(path,"{\"schema_version\":1,\"id\":\"test:cycle\",\"extends\":\"test:cycle\",\"components\":[],\"properties\":[]}");
  var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));assertTrue(error.getMessage().contains(path));assertTrue(error.getMessage().contains("cycle"));
  records.put(path,"{\"schema_version\":1,\"id\":\"test:unused\",\"components\":[\"Missing\"],\"properties\":[\"test:missing\"]}");
  assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records)).getMessage().contains("basis.properties"));
 }
 @Test void sparseAssaysRejectUnknownIdentitiesAndConflictingPositionalForms() {
  var c=MaterialCatalog.bundled();var records=new HashMap<>(c.resources());String path=ROOT+"assays/tjl20.json";
  var a=JsonParser.parseString(records.get(path)).getAsJsonObject();a.getAsJsonObject("amounts_by_component").addProperty("retired_pc13",0);records.put(path,a.toString());
  assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));
  a.getAsJsonObject("amounts_by_component").remove("retired_pc13");a.add("amounts",new JsonArray());records.put(path,a.toString());
  assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));
 }
 @Test void identityProjectionPreservesAmountsAndRejectsNonzeroRetiredSpecies() {
  var source=new MaterialAxis(List.of("Water","Ethane","Nitrogen"));var target=new MaterialAxis(List.of("Nitrogen","Water","Ethane","Propane"));
  assertArrayEquals(new double[]{3,1,2,0},target.project(source,new double[]{1,2,3}));
  assertThrows(IllegalArgumentException.class,()->new MaterialAxis(List.of("Ethane")).project(source,new double[]{1,2,0}));
  assertArrayEquals(new double[]{2},new MaterialAxis(List.of("Ethane")).project(source,new double[]{0,2,0}));
  assertThrows(IllegalArgumentException.class,()->new MaterialAxis(List.of("Ethane","Ethane")));
 }
}
