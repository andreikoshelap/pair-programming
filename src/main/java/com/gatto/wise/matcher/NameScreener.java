package com.gatto.wise.matcher;

import java.util.*;

import static com.gatto.wise.matcher.NameUtils.normalize;
import static com.gatto.wise.matcher.NameUtils.trigrams;

public class NameScreener {

    private static final Integer MIN_TRIGRAM_OVERLAP = 2;
    private final double threshold;
    private final Map<String, Set<Integer>> trigramIndex = new HashMap<>();
    private final List<SanctionedEntity> entities;
    private final List<NameMatcher> matchers;

    public NameScreener(Collection<SanctionedEntity> sanctionList, double threshold, List<NameMatcher> matchers) {
        this.entities = List.copyOf(Objects.requireNonNull(sanctionList, "sanctionList"));
        this.matchers = List.copyOf(Objects.requireNonNull(matchers, "matchers"));
        this.threshold = threshold;
        for (int i = 0; i < entities.size(); i++) {
            SanctionedEntity entity = entities.get(i);
            indexName(Objects.requireNonNull(entity.fullName(), "entity.fullName"), i);
            for (String alias : Objects.requireNonNull(entity.aliases(), "entity.aliases")) {
                indexName(Objects.requireNonNull(alias, "entity.aliases element"), i);
            }
        }
    }

    private void indexName(String name, int entityIndex) {
        for (String token : normalize(name).split(" ")) {
            for (String trigram : trigrams(token)) {
                trigramIndex.computeIfAbsent(trigram, k -> new HashSet<>()).add(entityIndex);
            }
        }
    }

    public List<ScreeningHit> screen(String customerName) {
        Objects.requireNonNull(customerName, "customerName");
        Map<Integer, Integer> candidateOverlap = new HashMap<>();
        for (String token : normalize(customerName).split(" ")) {
            for (String trigram : trigrams(token)) {
                for (Integer entityIndex : trigramIndex.getOrDefault(trigram, Set.of())) {
                    candidateOverlap.merge(entityIndex, 1, Integer::sum);
                }
            }
        }

        List<ScreeningHit> hits = new ArrayList<>();
        for (Integer entityIndex : candidateOverlap.keySet()) {
            if (candidateOverlap.get(entityIndex) < MIN_TRIGRAM_OVERLAP) {
                continue;
            }
            SanctionedEntity entity = entities.get(entityIndex);
            double score = bestScore(customerName, entity);

            if (score >= threshold) {
                hits.add(new ScreeningHit(entity.id(), entity.fullName(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(ScreeningHit::score).reversed());
        return hits;
    }

    private double bestScore(String customerName, SanctionedEntity entity) {
        double best = 0.0;
        best = Math.max(best, bestScoreAgainstName(customerName, entity.fullName()));
        for (String alias : entity.aliases()) {
            best = Math.max(best, bestScoreAgainstName(customerName, alias));
        }
        return best;
    }

    private double bestScoreAgainstName(String customerName, String candidateName) {
        double best = 0.0;
        for (NameMatcher matcher : matchers) {
            best = Math.max(best, matcher.similarity(customerName, candidateName));
        }
        return best;
    }
}

