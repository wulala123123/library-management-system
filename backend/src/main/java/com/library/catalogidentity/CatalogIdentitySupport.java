package com.library.catalogidentity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Normalization produces review candidates; it never chooses a stable identity. */
final class CatalogIdentitySupport {
    private CatalogIdentitySupport() {}

    static String normalized(String value) {
        if (value == null) return null;
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String match(String raw, String name, String rawNorm, String nameNorm) {
        if (raw == null || name == null) return "UNMATCHED";
        if (Objects.equals(raw, name)) return "EXACT";
        if (Objects.equals(rawNorm, nameNorm)) return "NORMALIZED";
        int max = Math.max(1, Math.min(3, Math.min(rawNorm.length(), nameNorm.length()) / 5));
        if (Math.abs(rawNorm.length() - nameNorm.length()) > max
                || rawNorm.length() > 200 || nameNorm.length() > 200) return "UNMATCHED";
        return distance(rawNorm, nameNorm) <= max ? "APPROXIMATE" : "UNMATCHED";
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
