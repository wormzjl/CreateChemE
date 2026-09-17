package com.wormzjl.createcheme.science.column.v3;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
class V3RequestedRecoveryTest {
 private static final Gson JSON=new Gson();
 @Test void coarseRecoveryNeverPublishesTheDrySurrogateForAnAuthoredFeatureRequest() throws Exception {
  V3ColumnInput input;
  try(var stream=getClass().getResourceAsStream("/science/column/v3/regrouped-authored-feature-recovery.json")) {
   assertNotNull(stream);input=input(JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("input"));
  }
  var captured=new AtomicReference<V3NeuralSeed>();
  var result=V3ColumnCalculator.calculateWithAcceptedProfile(input,V3SolveControl.UNBOUNDED,captured::set);
  if(result instanceof V3ColumnOutcome.Success success) {
   assertNotNull(captured.get());assertEquals(input,captured.get().input(),"No accepted surrogate may replace the authored request");
   assertEquals(input.sideDraws().size(),success.result().streams().stream().filter(s->s.streamId().startsWith("side_draw")).count());
   assertTrue(success.result().convergenceEvidence().satisfiesGates());
  } else assertNull(captured.get(),"A failed requested calculation must not export an intermediate label");
 }
    private static V3ColumnInput input(JsonObject json) {
        var specs=new ArrayList<V3ColumnSpecification>();
        for(var value:json.getAsJsonArray("specifications")) {
            var s=value.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(),json.get("packageId").getAsString(),json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"),String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"),double[].class),json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(),json.get("feedStageNumber").getAsInt(),json.get("topPressurePascal").getAsDouble(),
                json.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"),V3PumparoundSpec[].class)));
    }
}
