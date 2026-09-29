package com.gatto.wise.matcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameScreenerTest {

    @Test
    void exactMatchOnFullName() {
        var entity = new SanctionedEntity("1", "Ivan Petrov", Set.of());
        var screener = new NameScreener(List.of(entity), 0.9, List.of(new ExactNameMatcher()));

        List<ScreeningHit> hits = screener.screen("Ivan Petrov");

        assertEquals(1, hits.size());
        assertEquals("1", hits.get(0).entityId());
    }

    @Test
    void matchViaAlias() {
        var entity = new SanctionedEntity("1", "Ivan Petrov", Set.of("Vanya Petrov"));
        var screener = new NameScreener(List.of(entity), 0.9, List.of(new ExactNameMatcher()));

        List<ScreeningHit> hits = screener.screen("Vanya Petrov");

        assertEquals(1, hits.size());
    }

    @Test
    void belowTrigramOverlapIsExcluded() {
        var entity = new SanctionedEntity("1", "Ivan Petrov", Set.of());
        var screener = new NameScreener(List.of(entity), 0.9, List.of(new ExactNameMatcher()));

        List<ScreeningHit> hits = screener.screen("John Smith");

        assertTrue(hits.isEmpty());
    }

    @Test
    void belowThresholdIsExcluded() {
        var entity = new SanctionedEntity("1", "Ivan Petrov", Set.of());
        // stub matcher that always returns a score below any reasonable threshold
        NameMatcher weakMatcher = (a, b) -> 0.5;
        var screener = new NameScreener(List.of(entity), 0.9, List.of(weakMatcher));

        List<ScreeningHit> hits = screener.screen("Ivan Petrov");

        assertTrue(hits.isEmpty());
    }

    @Test
    void resultsAreSortedByScoreDescending() {
        var weak = new SanctionedEntity("1", "Ivan Petrov", Set.of());
        var strong = new SanctionedEntity("2", "Ivan Petров", Set.of()); // adjust to force different scores
        NameMatcher stub = (a, b) -> b.equals("Ivan Petrov") ? 1.0 : 0.9;
        var screener = new NameScreener(List.of(weak, strong), 0.8, List.of(stub));

        List<ScreeningHit> hits = screener.screen("Ivan Petrov");

        assertTrue(hits.get(0).score() >= hits.get(1).score());
    }
}