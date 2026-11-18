package ru.hw.sentiment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SentimentController {

    private final NaiveBayesModel model;
    private final MeterRegistry registry;
    private final Timer inferenceTimer;
    private final String pod;

    public SentimentController(NaiveBayesModel model, MeterRegistry registry) throws Exception {
        this.model = model;
        this.registry = registry;
        this.inferenceTimer = Timer.builder("sentiment.inference")
                .description("Время работы модели тональности")
                .publishPercentileHistogram()
                .register(registry);
        this.pod = InetAddress.getLocalHost().getHostName();   // имя пода в Kubernetes
    }

    /** GET /api/sentiment?text=hello  ->  {"sentiment":"positive", ...} */
    @GetMapping("/sentiment")
    public Map<String, Object> analyze(@RequestParam("text") String text) {
        NaiveBayesModel.Prediction p = inferenceTimer.record(() -> model.predict(text));
        Counter.builder("sentiment.predictions").tag("sentiment", p.sentiment())
                .description("Количество предсказаний по классам").register(registry).increment();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("text", text);
        r.put("sentiment", p.sentiment());
        r.put("confidence", p.confidence());
        r.put("probabilities", p.probabilities());
        r.put("pod", pod);
        return r;
    }

    /** POST /api/sentiment  {"text": "..."} */
    @PostMapping("/sentiment")
    public Map<String, Object> analyzePost(@RequestBody Map<String, String> body) {
        return analyze(body.getOrDefault("text", ""));
    }

    /** Информация о модели и поде, который обработал запрос. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of("model", "multinomial-naive-bayes",
                "classes", new String[]{"positive", "negative", "neutral"},
                "trainingExamples", model.trainingSize(),
                "vocabulary", model.vocabularySize(),
                "pod", pod);
    }
}
