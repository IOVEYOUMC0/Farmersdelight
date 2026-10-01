package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles ideal vectors at publication time; matching shares at most four input views. */
public final class FuzzyRecipeMatcher {
    private FuzzyRecipeMatcher() { }

    public enum Quality {
        SUPERB(1.2, "gold"), EXCELLENT(0.9, "green"), STANDARD(0.6, "white"), POOR(0.3, "dark_gray");
        public final double foodMultiplier;
        public final String color;
        Quality(double foodMultiplier, String color) { this.foodMultiplier = foodMultiplier; this.color = color; }
        static Quality fromDeviation(long deviation) { return values()[(int) Math.min(3, Math.max(0, deviation))]; }
    }

    public record Definition(String id, FuzzyRecipeSpec spec, int priority) { }
    private record Vector(Map<String, Integer> counts, double norm) { }
    private record Candidate(Definition definition, Vector ideal, int view, int order) { }
    public record Match(String id, double similarity, int portions, Quality quality) { }

    public static final class Index {
        private final List<Map<String, List<Candidate>>> anchors;
        private final boolean empty;
        private final FoodGroupSnapshot groups;
        private Index(List<Candidate> candidates, FoodGroupSnapshot groups) {
            empty = candidates.isEmpty();
            List<Map<String, Integer>> frequencies = new ArrayList<>(4);
            List<Map<String, List<Candidate>>> mutable = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) { frequencies.add(new HashMap<>()); mutable.add(new HashMap<>()); }
            for (Candidate candidate : candidates) {
                for (String key : candidate.ideal().counts().keySet()) frequencies.get(candidate.view()).merge(key, 1, Integer::sum);
            }
            for (Candidate candidate : candidates) {
                Map<String, Integer> frequency = frequencies.get(candidate.view());
                String anchor = candidate.ideal().counts().keySet().stream()
                        .min(java.util.Comparator.<String>comparingInt(frequency::get).thenComparing(java.util.Comparator.naturalOrder())).orElseThrow();
                mutable.get(candidate.view()).computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(candidate);
            }
            List<Map<String, List<Candidate>>> frozen = new ArrayList<>(4);
            for (var index : mutable) {
                Map<String, List<Candidate>> copy = new HashMap<>();
                index.forEach((key, value) -> copy.put(key, List.copyOf(value)));
                frozen.add(Map.copyOf(copy));
            }
            anchors = List.copyOf(frozen);
            this.groups = groups;
        }
        public boolean isEmpty() { return empty; }

        public Match match(Map<String, Integer> inputs) {
            if (inputs.isEmpty()) return null;
            Candidate best = null;
            Vector bestInput = null;
            double bestScore = -1;
            for (int view = 0; view < 4; view++) {
                Map<String, List<Candidate>> index = anchors.get(view);
                if (index.isEmpty()) continue;
                Vector actual = vector(inputs, groups, (view & 1) != 0, (view & 2) != 0);
                if (actual.norm() == 0) continue;
                for (String ingredient : actual.counts().keySet()) {
                    for (Candidate candidate : index.getOrDefault(ingredient, List.of())) {
                        if (!actual.counts().keySet().containsAll(candidate.ideal().counts().keySet())) continue;
                        double dot = 0;
                        for (var entry : candidate.ideal().counts().entrySet()) dot += (double) entry.getValue() * actual.counts().get(entry.getKey());
                        double score = Math.min(1, dot / (actual.norm() * candidate.ideal().norm()));
                        if (score < candidate.definition().spec().minimumScore()) continue;
                        if (best == null || candidate.definition().priority() > best.definition().priority()
                                || candidate.definition().priority() == best.definition().priority()
                                && (candidate.ideal().counts().size() > best.ideal().counts().size()
                                || candidate.ideal().counts().size() == best.ideal().counts().size()
                                && (score > bestScore + 1e-12 || Math.abs(score - bestScore) <= 1e-12 && candidate.order() < best.order()))) {
                            best = candidate;
                            bestInput = actual;
                            bestScore = score;
                        }
                    }
                }
            }
            if (best == null) return null;
            int portions = Integer.MAX_VALUE;
            for (var entry : best.ideal().counts().entrySet()) portions = Math.min(portions, bestInput.counts().get(entry.getKey()) / entry.getValue());
            portions = Math.max(1, portions);
            long deviation = 0;
            for (var entry : bestInput.counts().entrySet()) {
                deviation += Math.abs((long) entry.getValue() - (long) best.ideal().counts().getOrDefault(entry.getKey(), 0) * portions);
            }
            return new Match(best.definition().id(), bestScore, portions, Quality.fromDeviation(deviation));
        }
    }

    public static Index compile(List<Definition> definitions, FoodGroupSnapshot groups) {
        List<Candidate> candidates = new ArrayList<>(definitions.size());
        for (Definition definition : definitions) {
            FuzzyRecipeSpec spec = definition.spec();
            int view = (spec.useEquivalentFoods() && !groups.equivalents().isEmpty() ? 1 : 0)
                    | (spec.useSeasonings() && !groups.seasonings().isEmpty() ? 2 : 0);
            // Seasoning suppression applies to the input only; a declared required ingredient stays required.
            Vector ideal = vector(spec.perfect(), groups, spec.useEquivalentFoods(), false);
            candidates.add(new Candidate(definition, ideal, view, candidates.size()));
        }
        return new Index(candidates, groups);
    }

    private static Vector vector(Map<String, Integer> source, FoodGroupSnapshot groups, boolean equivalent, boolean seasoning) {
        Map<String, Integer> counts = new HashMap<>(source.size());
        source.forEach((id, count) -> {
            if (count == null || count <= 0 || seasoning && groups.seasonings().contains(id)) return;
            counts.merge(equivalent ? groups.canonical(id) : id, count, Math::addExact);
        });
        double squared = 0;
        for (int count : counts.values()) squared += (double) count * count;
        return new Vector(Map.copyOf(counts), Math.sqrt(squared));
    }

}
