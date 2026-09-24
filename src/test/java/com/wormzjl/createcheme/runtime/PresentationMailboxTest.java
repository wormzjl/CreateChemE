package com.wormzjl.createcheme.runtime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationMailboxTest {
    @Test void inputsWaitForOnlineDeadlineAndAllApplyBeforeAnyView() {
        var box = new PresentationMailbox<String>(); var log = new ArrayList<String>();
        assertTrue(box.submit("a", 124, () -> log.add("input-a")));
        assertTrue(box.submit("b", 199, () -> log.add("input-b")));
        box.tick(199, key -> true, key -> log.add("view-" + key)); assertTrue(log.isEmpty());
        box.tick(200, key -> true, key -> log.add("view-" + key));
        assertEquals(List.of("input-a","input-b","view-a","view-b"), log);
        box.tick(201, key -> true, key -> fail("early view"));
        box.tick(300, key -> true, key -> log.add("next-" + key));
        assertEquals(6, log.size()); assertEquals(400, box.nextTick());
    }
    @Test void duplicateInputCannotReplaceAnUnacknowledgedCommand() {
        var box = new PresentationMailbox<String>(); var log = new ArrayList<String>();
        assertTrue(box.submit("a", 0, () -> log.add("first")));
        assertFalse(box.submit("a", 0, () -> log.add("second")));
        box.tick(100, key -> true, key -> {});
        assertEquals(List.of("first"), log);
    }
    @Test void closedSessionsExpireTheirUnsentCommands() {
        var box = new PresentationMailbox<String>();
        box.submit("closed", 0, () -> fail("closed session acted"));
        box.tick(100, key -> false, key -> fail("closed session published"));
        assertEquals(0, box.size()); assertEquals(Long.MAX_VALUE, box.nextTick());
        box.subscribe("reopened", 8137);
        assertEquals(8200, box.nextTick(), "restored engine epoch, not wall-clock or a new zero epoch");
    }
}
