package com.aravindakv.hiretrack.feed.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Spec 3.6 step 3: rank by how many distinct words a posting shares with the resume. No AI, no cost. */
public final class Prefilter {

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "you", "our", "are", "will", "your",
            "this", "that", "have", "from", "has", "who", "all", "can", "job", "role", "work", "team", "years", "year");

    private Prefilter() {}

    public record Candidate(long postingId, String text, long postedEpoch) {}

    public record Ranked(long postingId, int score) {}

    public static Set<String> words(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+"))
                .map(w -> w.replaceAll("^\\.+|\\.+$", ""))
                .filter(w -> w.length() >= 3 && !STOP.contains(w))
                .collect(Collectors.toSet());
    }

    /** Highest overlap first; newer postings win ties. */
    public static List<Ranked> top(List<Candidate> candidates, String resume, int n) {
        Set<String> resumeWords = words(resume);
        return candidates.stream()
                .map(c -> {
                    Set<String> w = words(c.text());
                    w.retainAll(resumeWords);
                    return new Object[]{c, w.size()};
                })
                .sorted(Comparator.<Object[]>comparingInt(o -> -(int) o[1])
                        .thenComparingLong(o -> -((Candidate) o[0]).postedEpoch()))
                .limit(n)
                .map(o -> new Ranked(((Candidate) o[0]).postingId(), (int) o[1]))
                .toList();
    }
}