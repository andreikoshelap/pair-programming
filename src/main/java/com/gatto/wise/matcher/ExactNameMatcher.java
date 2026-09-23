package com.gatto.wise.matcher;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class ExactNameMatcher implements NameMatcher {

    static Set<String> trigrams(String word) {
        Set<String> result = new HashSet<>();
        if (word.length() < 3) {
            result.add(word);
            return result;
        }
        for (int i = 0; i <= word.length() - 3; i++) {
            result.add(word.substring(i, i + 3));
        }
        return result;
    }
    @Override
    public double similarity(String customerName, String listedName) {
        return normalize(customerName).equals(normalize(listedName)) ? 1.0 : 0.0;
    }

    static String normalize(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String withoutMarks = decomposed.replaceAll("\\p{M}", "");
        String lower = withoutMarks.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return cleaned.trim();
    }
}