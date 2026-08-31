package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Captures caught flash exceptions and local inputs in an owned debugger JVM, without production edits. */
public final class V3FlashFailureCapture {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String FLASH_CLASS = "com.wormzjl.createcheme.science.column.v3.thermo.V3FeedFlash";

    private V3FlashFailureCapture() {}

    public static void main(String[] args) throws Exception {
        boolean child = Arrays.asList(args).contains("--child");
        double pressure = Double.parseDouble(option(args, "pressureKpa", "100"));
        double cutoff = Double.parseDouble(option(args, "cutoff", "0"));
        Path reportPath = Path.of(option(args, "report", "build/reports/benchmarks/v3-flash-capture.json")).toAbsolutePath();
        if (!Double.isFinite(pressure) || pressure < 50 || pressure > 250) throw new IllegalArgumentException("Invalid diagnostic pressure");
        V3TruncationSupport.requireCutoff(cutoff);
        Files.createDirectories(reportPath.getParent());
        if (child) {
            V3ColumnInput input = V3TimeoutBenchmark.input(new V3TimeoutBenchmark.Scenario("flash-capture", pressure, 2610.7, 8, cutoff));
            long started = System.nanoTime();
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - started > 120_000_000_000L) throw new CancellationException("debugger diagnostic deadline");
            }, cutoff);
            Files.writeString(Path.of(reportPath + "-outcome.json"), JSON.toJson(outcome));
            System.out.println("CHILD outcome=" + outcome.getClass().getSimpleName()
                    + (outcome instanceof V3ColumnOutcome.Failure failure ? " code=" + failure.code() + " summary=" + failure.summary() : ""));
            return;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("started", Instant.now().toString());
        report.put("requestedPressureKpa", pressure);
        report.put("cutoff", cutoff);
        report.put("note", "Debugger observation only; no production code modified. All V3FeedFlash exception sites are captured, including internally caught failures. Timing is not a benchmark.");
        List<Map<String, Object>> captures = new ArrayList<>();
        report.put("events", captures);
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> launch = connector.defaultArguments();
        launch.get("home").setValue(System.getProperty("java.home"));
        launch.get("options").setValue("--add-modules=jdk.jdi -Xms512m -Xmx2g -cp \"" + System.getProperty("java.class.path") + "\"");
        launch.get("main").setValue(V3FlashFailureCapture.class.getName() + " --child --pressureKpa=" + pressure
                + " --cutoff=" + cutoff + " \"--report=" + reportPath + "\"");
        VirtualMachine vm = connector.launch(launch);
        Process process = vm.process();
        Thread stdout = drain(process.getInputStream());
        Thread stderr = drain(process.getErrorStream());
        long deadline = System.nanoTime() + 150_000_000_000L;
        boolean done = false;
        try {
            ExceptionRequest exceptions = vm.eventRequestManager().createExceptionRequest(null, true, true);
            exceptions.addClassFilter(FLASH_CLASS);
            exceptions.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            exceptions.enable();
            vm.resume();
            while (!done && System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                try {
                    for (Event event : events) {
                        if (event instanceof ExceptionEvent exception) {
                            Map<String, Object> captured = capture(exception);
                            captures.add(captured);
                            Files.writeString(reportPath, JSON.toJson(report));
                            System.out.println("CAPTURE index=" + (captures.size() - 1) + " T=" + captured.get("temperatureKelvin")
                                    + " P=" + captured.get("pressurePascal") + " message=" + captured.get("message"));
                            System.out.println("STACK " + captured.get("stack"));
                        } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) done = true;
                    }
                } finally {
                    if (!done) events.resume();
                }
            }
            report.put("completed", done);
        } catch (VMDisconnectedException disconnected) {
            report.put("completed", true);
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException alreadyClosed) { /* Owned VM has exited. */ }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Owned diagnostic JVM did not terminate");
            stdout.join(1000);
            stderr.join(1000);
            report.put("childExitCode", process.exitValue());
            report.put("finished", Instant.now().toString());
            Files.writeString(reportPath, JSON.toJson(report));
        }
        System.out.println("REPORT " + reportPath);
    }

    private static Thread drain(java.io.InputStream stream) {
        return Thread.ofPlatform().daemon(true).start(() -> {
            try (stream) { stream.transferTo(System.out); }
            catch (IOException closed) { System.out.println("Diagnostic child stream closed: " + closed.getMessage()); }
        });
    }

    private static Map<String, Object> capture(ExceptionEvent event) throws Exception {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("packageId", "createcheme:cdu17_tjl_acs2018");
        capture.put("message", scalar(field(event.exception(), "detailMessage")));
        capture.put("catchLocation", event.catchLocation() == null ? null : event.catchLocation().toString());
        List<StackFrame> frames = event.thread().frames();
        capture.put("stack", frames.stream().map(frame -> frame.location().toString()).toList());
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (StackFrame frame : frames) {
            Map<String, Value> locals = new LinkedHashMap<>();
            try {
                for (LocalVariable variable : frame.visibleVariables()) locals.put(variable.name(), frame.getValue(variable));
            } catch (AbsentInformationException hiddenFrame) {
                // Generated lambda/JDK frames can omit local-variable tables; retain their stack location.
            }
            if (frame.location().declaringType().name().equals(FLASH_CLASS) && frame.location().method().name().equals("resolve")) {
                capture.put("temperatureKelvin", scalar(locals.get("temperatureKelvin")));
                capture.put("pressurePascal", scalar(locals.get("pressurePascal")));
                capture.put("initialLiquidEndpoint", scalar(locals.get("atLiquidEndpoint")));
                capture.put("initialVaporEndpoint", scalar(locals.get("atVaporEndpoint")));
                ObjectReference workspace = (ObjectReference) locals.get("workspace");
                for (String name : List.of("normalizedOverall", "wilsonK", "logK", "nextLogK", "liquidComposition", "vaporComposition")) {
                    capture.put(name, scalar(field(workspace, name)));
                }
            }
            if (frame.location().method().name().equals("flashTP") && locals.containsKey("overallComposition")) {
                capture.put("authoredFlashInput", scalar(locals.get("overallComposition")));
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("location", frame.location().toString());
            for (Map.Entry<String, Value> local : locals.entrySet()) {
                if (local.getValue() instanceof PrimitiveValue || local.getValue() instanceof StringReference) {
                    context.put(local.getKey(), scalar(local.getValue()));
                }
            }
            Value input = locals.get("input");
            Value problem = locals.get("problem");
            if (problem == null && frame.thisObject() != null) problem = field(frame.thisObject(), "problem");
            if (input == null && problem instanceof ObjectReference object) input = field(object, "input");
            if (input instanceof ObjectReference object) {
                for (String name : List.of("stageCount", "feedStageNumber", "topPressurePascal", "stagePressureDropPascal", "feedTemperatureKelvin")) {
                    context.put(name, scalar(field(object, name)));
                }
            }
            contexts.add(context);
        }
        capture.put("contexts", contexts);
        return capture;
    }

    private static Value field(ObjectReference object, String name) {
        if (object == null) return null;
        Field selected = object.referenceType().allFields().stream().filter(field -> field.name().equals(name)).findFirst().orElse(null);
        return selected == null ? null : object.getValue(selected);
    }

    private static Object scalar(Value value) {
        if (value == null) return null;
        if (value instanceof StringReference string) return string.value();
        if (value instanceof PrimitiveValue primitive) {
            if (primitive.type().name().equals("boolean")) return primitive.booleanValue();
            return primitive.doubleValue();
        }
        if (value instanceof ArrayReference array) return array.getValues().stream().map(V3FlashFailureCapture::scalar).toList();
        return value.toString();
    }

    private static String option(String[] args, String name, String fallback) {
        String prefix = "--" + name + "=";
        return Arrays.stream(args).filter(arg -> arg.startsWith(prefix)).map(arg -> arg.substring(prefix.length())).findFirst().orElse(fallback);
    }
}
