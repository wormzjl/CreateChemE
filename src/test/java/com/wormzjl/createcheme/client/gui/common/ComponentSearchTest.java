package com.wormzjl.createcheme.client.gui.common;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSearchTest {
    private static ComponentSearch.Option option(int id,String name){return new ComponentSearch.Option(id,name,name);}
    private static final List<ComponentSearch.Option> OPTIONS=List.of(option(0,"Methane"),option(1,"Ethane"),option(2,"Nitrogen"),option(3,"Water"),option(4,"Isobutane"));
    private static List<Integer> ids(String query){return ComponentSearch.rank(OPTIONS,query).stream().map(ComponentSearch.Option::id).toList();}
    @Test void exactNamesOutrankPrefixAndSubstringMatches(){
        assertEquals(1,ids("ethane").getFirst());assertEquals(0,ids("meth").getFirst());assertEquals(2,ids("NITROGEN").getFirst());
    }
    @Test void missingCharactersAndTransposedLettersStillFindTheIntendedComponent(){
        assertEquals(0,ids("methne").getFirst());assertEquals(1,ids("ethnae").getFirst());assertEquals(2,ids("ntgn").getFirst());
    }
    @Test void localizedNamesAndStableIdentitiesAreBothSearchable(){
        var entries=List.of(new ComponentSearch.Option(8,"Wässer","Water"),new ComponentSearch.Option(9,"氮气","Nitrogen"));
        assertEquals(8,ComponentSearch.rank(entries,"wasser").getFirst().id());
        assertEquals(8,ComponentSearch.rank(entries,"water").getFirst().id());
        assertEquals(9,ComponentSearch.rank(entries,"氮").getFirst().id());
        assertEquals(9,ComponentSearch.rank(entries,"nitro").getFirst().id());
    }
    @Test void multipleWordsCanBeTypedInAnyOrderAndPunctuationIsNormalized(){
        var entries=List.of(option(1,"Crude oil, NBP 84.3–134.5°C"),option(2,"Crude oil, NBP 134.5–182.9°C"));
        assertEquals(List.of(1),ComponentSearch.rank(entries,"134 84 nbp").stream().map(ComponentSearch.Option::id).toList());
    }
    @Test void noMatchDoesNotOfferAnUnrelatedComponentAndEmptySearchKeepsCatalogOrder(){
        assertTrue(ids("xyzzy").isEmpty());assertEquals(List.of(0,1,2,3,4),ids("  "));
        var available=OPTIONS.stream().filter(o->o.id()!=0).toList();
        assertTrue(ComponentSearch.rank(available,"methane").stream().noneMatch(o->o.id()==0));
    }
    @Test void matchingDoesNotMutateTheCatalog(){
        var copy=new ArrayList<>(OPTIONS);ComponentSearch.rank(copy,"eth");assertEquals(OPTIONS,copy);
    }
}
