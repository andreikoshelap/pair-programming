package com.gatto.wise.matcher;

import java.util.*;

import static com.gatto.wise.matcher.ExactNameMatcher.normalize;
import static com.gatto.wise.matcher.ExactNameMatcher.trigrams;

public class NameScreener {

    private static final Integer MIN_TRIGRAM_OVERLAP = 2;
    double threshold = 0.9;
    private final Map<String, Set<Integer>> trigramIndex = new HashMap<>();
    private final List<SanctionedEntity> entities;

    public NameScreener(Collection<SanctionedEntity> sanctionList, double threshold) {
        this.entities = List.copyOf(sanctionList);
        for (int i = 0; i < entities.size(); i++) {
            SanctionedEntity entity = entities.get(i);
            indexName(entity.fullName(), i);
            for (String alias : entity.aliases()) {
                indexName(alias, i);
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
            // TODO: best score across fullName and all aliases
            double score = bestScore(customerName, entity);

            if (score >= threshold) {
                hits.add(new ScreeningHit(entity.id(), entity.fullName(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(ScreeningHit::score).reversed());
        return hits;
    }

    private double bestScore(String customerName, SanctionedEntity entity) {
        return 0.0;
    }
}

