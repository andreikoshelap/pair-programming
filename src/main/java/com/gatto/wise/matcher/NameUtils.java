package com.gatto.wise.matcher;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class NameUtils {

    private NameUtils() {
    }

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

    static String normalize(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String withoutMarks = decomposed.replaceAll("\\p{M}", "");
        String lower = withoutMarks.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return cleaned.trim();
    }
}
