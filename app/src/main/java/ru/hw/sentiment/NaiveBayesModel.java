package ru.hw.sentiment;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Мультиномиальный наивный байесовский классификатор тональности.
 * Обучается при старте приложения на размеченном наборе фраз (resources/train.tsv),
 * классы: positive / negative / neutral. Сглаживание Лапласа, признаки — слова.
 */
@Component
public class NaiveBayesModel {

    public record Prediction(String sentiment, double confidence, Map<String, Double> probabilities) {}

    private final Map<String, Map<String, Integer>> wordCounts = new HashMap<>();
    private final Map<String, Integer> totalWords = new HashMap<>();
    private final Map<String, Integer> docCounts = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();
    private int totalDocs;

    public NaiveBayesModel() throws IOException {
        var res = new ClassPathResource("train.tsv");
        try (var in = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", 2);
                train(p[0].trim(), p[1]);
            }
        }
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (t.length() > 1) out.add(t);
        }
        return out;
    }

    private void train(String label, String text) {
        totalDocs++;
        docCounts.merge(label, 1, Integer::sum);
        var counts = wordCounts.computeIfAbsent(label, k -> new HashMap<>());
        for (String w : tokenize(text)) {
            counts.merge(w, 1, Integer::sum);
            totalWords.merge(label, 1, Integer::sum);
            vocabulary.add(w);
        }
    }

    public Prediction predict(String text) {
        List<String> tokens = tokenize(text);
        Map<String, Double> logP = new LinkedHashMap<>();
        for (String label : List.of("positive", "negative", "neutral")) {
            double lp = Math.log((double) docCounts.getOrDefault(label, 0) / totalDocs);
            var counts = wordCounts.getOrDefault(label, Map.of());
            double denom = totalWords.getOrDefault(label, 0) + vocabulary.size();
            for (String w : tokens) {
                if (!vocabulary.contains(w)) continue;           // незнакомые слова не влияют
                lp += Math.log((counts.getOrDefault(w, 0) + 1.0) / denom);
            }
            logP.put(label, lp);
        }
        // softmax по логарифмам вероятностей -> уверенность модели
        double max = Collections.max(logP.values());
        double sum = logP.values().stream().mapToDouble(v -> Math.exp(v - max)).sum();
        Map<String, Double> probs = new LinkedHashMap<>();
        logP.forEach((k, v) -> probs.put(k, Math.round(Math.exp(v - max) / sum * 1000) / 1000.0));
        String best = probs.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Prediction(best, probs.get(best), probs);
    }

    public int vocabularySize() { return vocabulary.size(); }
    public int trainingSize() { return totalDocs; }
}
