package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

class FairIslandQueueTest {
    @Test void catchupYieldsToAnotherReadyOwnerAndUnavailableOwnersDoNotWasteWorkers() {
        var queue=new FairIslandQueue();queue.register(1);queue.register(2);queue.register(3);queue.register(1);
        assertEquals(3,queue.size());assertEquals(1,queue.nextReady(id->id!=2).orElseThrow());
        assertEquals(3,queue.nextReady(id->id!=2).orElseThrow());assertEquals(2,queue.nextReady(id->true).orElseThrow());
        assertEquals(1,queue.nextReady(id->true).orElseThrow());queue.remove(2);assertEquals(2,queue.size());
        assertTrue(queue.nextReady(id->false).isEmpty());assertEquals(3,queue.nextReady(id->true).orElseThrow());
    }

    /** The queue as it was before owners were indexed by readiness: every owner rotates through one deque. */
    private static final class RotatingDeque {
        private final ArrayDeque<Long> order=new ArrayDeque<>(),newcomers=new ArrayDeque<>();
        private final Set<Long> registered=new HashSet<>();
        void register(long island){if(registered.add(island))newcomers.addLast(island);}
        void remove(long island){if(registered.remove(island)){order.remove(island);newcomers.remove(island);}}
        OptionalLong nextReady(LongPredicate ready) {
            for(var iterator=newcomers.iterator();iterator.hasNext();) {
                long island=iterator.next();if(ready.test(island)){iterator.remove();order.addLast(island);return OptionalLong.of(island);}
            }
            int candidates=order.size();
            for(int i=0;i<candidates;i++){long island=order.removeFirst();order.addLast(island);if(ready.test(island))return OptionalLong.of(island);}
            return OptionalLong.empty();
        }
    }

    /** Serving only indexed ready owners gives exactly the service sequence of rotating every owner. */
    @Test void theReadyIndexServesTheRotatingDequeOrderUnderArbitraryChurn() {
        var random=new Random(20260923);long served=0;
        for(int trial=0;trial<300;trial++) {
            var queue=new FairIslandQueue();var reference=new RotatingDeque();var ready=new HashSet<Long>();var veto=new HashSet<Long>();
            int owners=2+random.nextInt(40);
            for(int step=0;step<600;step++) {
                long id=1+random.nextInt(owners);int op=random.nextInt(12);
                if(op<2){boolean isReady=random.nextBoolean();if(!reference.registered.contains(id)){queue.register(id,isReady);reference.register(id);if(isReady)ready.add(id);else ready.remove(id);}}
                else if(op==2){queue.remove(id);reference.remove(id);ready.remove(id);}
                else if(op<6){boolean isReady=random.nextBoolean();queue.setReady(id,isReady);if(reference.registered.contains(id)){if(isReady)ready.add(id);else ready.remove(id);}}
                else if(op==6){veto.clear();for(long owner=1;owner<=owners;owner++)if(random.nextInt(4)==0)veto.add(owner);}
                else {
                    var expected=reference.nextReady(owner->ready.contains(owner)&&!veto.contains(owner));
                    var actual=queue.nextReady(owner->!veto.contains(owner));
                    assertEquals(expected,actual,"trial "+trial+" step "+step);if(actual.isPresent())served++;
                }
                assertEquals(reference.registered.size(),queue.size());assertEquals(ready.size(),queue.readyCount());
            }
        }
        assertTrue(served>50_000,"the comparison must exercise real service, served "+served);
    }

    /** Many newcomers served while the position is mid-cycle exhaust the key gap there and force a re-key. */
    @Test void reKeyingAnExhaustedGapKeepsTheServiceOrder() {
        var queue=new FairIslandQueue();var reference=new RotatingDeque();
        for(long id=1;id<=10;id++){queue.register(id);reference.register(id);}
        for(int i=0;i<15;i++)assertEquals(reference.nextReady(id->true),queue.nextReady(id->true));
        for(long id=11;id<=110;id++){queue.register(id);reference.register(id);assertEquals(reference.nextReady(owner->true),queue.nextReady(owner->true));}
        for(int i=0;i<500;i++)assertEquals(reference.nextReady(id->id%3!=0),queue.nextReady(id->id%3!=0));
    }
}
