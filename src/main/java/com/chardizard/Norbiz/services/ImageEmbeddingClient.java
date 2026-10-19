package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.config.ItemRecognitionProperties;
import com.chardizard.Norbiz.exceptions.ItemRecognitionUnavailableException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Turns an image into a CLIP embedding via the embedder sidecar (embedder/app.py). The sidecar only
 * ever sees pixels — no item or company data. The RestClient carries the observation registry, so
 * each call is an HTTP client span under the request and propagates the trace to the sidecar.
 */
@Component
@EnableConfigurationProperties(ItemRecognitionProperties.class)
public class ImageEmbeddingClient {

    /** Must match the item_image_embeddings.embedding column, vector(512). */
    public static final int DIMENSION = 512;

    private final ItemRecognitionProperties properties;
    private final RestClient restClient;

    public ImageEmbeddingClient(ItemRecognitionProperties properties, ObservationRegistry observationRegistry) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getEmbedderUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    public record Embedding(String model, float[] vector) {
    }

    // The sidecar's /embed response body (embedder/app.py).
    record EmbedResponse(String model, int dim, float[] embedding) {
    }

    /**
     * @throws IllegalArgumentException the bytes aren't a decodable image (the sidecar's 4xx)
     * @throws ItemRecognitionUnavailableException disabled, or the sidecar is unreachable/failing
     */
    public Embedding embed(byte[] image) {
        if (!properties.isEnabled()) {
            throw new ItemRecognitionUnavailableException("Item photo recognition is disabled");
        }
        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(image)
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (HttpClientErrorException ex) {
            throw new IllegalArgumentException("The uploaded file is not a readable image");
        } catch (RestClientException ex) {
            throw new ItemRecognitionUnavailableException("Item photo recognition is unavailable right now", ex);
        }
        if (response == null || response.embedding() == null || response.embedding().length != DIMENSION) {
            throw new ItemRecognitionUnavailableException("Embedder returned an unexpected embedding (expected "
                    + DIMENSION + " dimensions; was the model changed without a migration?)");
        }
        return new Embedding(response.model(), response.embedding());
    }
}
