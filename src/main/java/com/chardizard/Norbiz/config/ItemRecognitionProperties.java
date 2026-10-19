package com.chardizard.Norbiz.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.item-recognition.*} — item photo recognition, see docs/ITEM_RECOGNITION.md.
 */
@Getter
@Setter
@ConfigurationProperties("app.item-recognition")
public class ItemRecognitionProperties {

    /** Master switch. When false recognition answers 503 and image uploads skip indexing. */
    private boolean enabled = true;

    /** Base URL of the embedder sidecar (embedder/). */
    private String embedderUrl = "http://localhost:8000";

    private Duration connectTimeout = Duration.ofSeconds(2);

    /** CPU inference of one image is ~0.1–0.5 s; the slack covers a cold start. */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * Cosine similarity (0..1) below which a candidate isn't returned at all. CLIP puts unrelated
     * products around 0.4–0.6 and photos of the same product typically above 0.8 — tune per catalog.
     */
    private double minSimilarity = 0.5;
}
