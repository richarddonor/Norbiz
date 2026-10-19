package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.config.ItemRecognitionProperties;
import com.chardizard.Norbiz.dto.ItemRecognitionMatchResponse;
import com.chardizard.Norbiz.exceptions.ItemRecognitionUnavailableException;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.models.ItemTag;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.ItemImageEmbeddingRepository;
import com.chardizard.Norbiz.repositories.ItemImageEmbeddingRepository.Match;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Item photo recognition (docs/ITEM_RECOGNITION.md): each item's reference image is embedded by the
 * CLIP sidecar and stored in item_image_embeddings; a query photo is embedded the same way and
 * matched by cosine similarity within one company.
 *
 * Deliberately not @Transactional at class level: the embedder call can take a while and must not
 * hold a database connection open.
 */
@Service
public class ItemRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(ItemRecognitionService.class);

    private final ImageEmbeddingClient embeddingClient;
    private final ItemImageEmbeddingRepository embeddingRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final LookupService lookupService;
    private final ItemRecognitionProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ObservationRegistry observationRegistry;
    private final Path uploadRoot;

    // One reindex job at a time per company; jobs run one after another on a single thread so a
    // big catalog can't monopolise the embedder.
    private final Set<Long> reindexing = ConcurrentHashMap.newKeySet();
    private final ExecutorService reindexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "item-image-reindex");
        t.setDaemon(true);
        return t;
    });

    public ItemRecognitionService(ImageEmbeddingClient embeddingClient, ItemImageEmbeddingRepository embeddingRepository,
                                  ItemRepository itemRepository, UserRepository userRepository, LookupService lookupService,
                                  ItemRecognitionProperties properties, TransactionTemplate transactionTemplate,
                                  ObservationRegistry observationRegistry,
                                  @Value("${app.item-image.upload-dir}") String uploadDir) {
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.lookupService = lookupService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.observationRegistry = observationRegistry;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        reindexExecutor.shutdownNow();
    }

    /**
     * The company's items that look most like the photo, best first. Only items with an indexed
     * reference image can match; candidates below {@code app.item-recognition.min-similarity} are
     * dropped, so an empty list means "nothing in the catalog looks like this".
     */
    public List<ItemRecognitionMatchResponse> recognize(String username, Long companyId, byte[] photo, ItemTag tag,
                                                        boolean activeOnly, int limit, boolean canViewCostPrice) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        assertCompanyAccess(username, companyId);
        if (photo == null || photo.length == 0) {
            throw new IllegalArgumentException("An image file is required");
        }

        ImageEmbeddingClient.Embedding query = embeddingClient.embed(photo);
        List<Match> matches = embeddingRepository.nearest(companyId, query.model(), query.vector(), activeOnly,
                tag != null ? tag.name() : null, properties.getMinSimilarity(), limit);
        List<ItemRecognitionMatchResponse> result = lookupService.itemMatches(matches, canViewCostPrice);
        log.info("User '{}' recognized item photo for company {}: {} candidate(s){}", username, companyId, result.size(),
                result.isEmpty() ? "" : " (best item id=" + result.getFirst().getItem().getId()
                        + ", similarity=" + String.format("%.3f", result.getFirst().getSimilarity()) + ")");
        return result;
    }

    /**
     * (Re)indexes the item's current image. Called right after an upload; a failure never fails the
     * upload itself — the item just can't be recognized until the next reindex.
     *
     * @return whether the image is now indexed
     */
    public boolean indexQuietly(Item item, byte[] image) {
        try {
            index(item.getCompany().getId(), item.getId(), item.getImagePath(), image);
            return true;
        } catch (ItemRecognitionUnavailableException | IllegalArgumentException ex) {
            log.warn("Item image for item id={} not indexed for recognition: {}", item.getId(), ex.getMessage());
            return false;
        }
    }

    /**
     * Queues a background job that indexes the company's item images: by default only those not yet
     * indexed, or all of them with {@code force} (e.g. after changing the embedder model).
     *
     * @return the number of items queued
     */
    public int reindex(String username, Long companyId, boolean force) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        assertCompanyAccess(username, companyId);
        if (!properties.isEnabled()) {
            throw new ItemRecognitionUnavailableException("Item photo recognition is disabled");
        }
        if (!reindexing.add(companyId)) {
            throw new IllegalArgumentException("Item images for company " + companyId + " are already being reindexed");
        }

        List<ItemImageRef> pending;
        try {
            Set<Long> indexed = force ? Set.of() : embeddingRepository.indexedItemIds(companyId);
            pending = itemRepository.findByCompany_IdAndImagePathIsNotNull(companyId).stream()
                    .filter(i -> !indexed.contains(i.getId()))
                    .map(i -> new ItemImageRef(i.getId(), i.getImagePath()))
                    .toList();
            reindexExecutor.submit(() -> runReindex(username, companyId, pending));
        } catch (RuntimeException ex) {
            reindexing.remove(companyId);
            throw ex;
        }
        log.info("User '{}' queued item image reindex for company {} ({} item(s), force={})",
                username, companyId, pending.size(), force);
        return pending.size();
    }

    private record ItemImageRef(long itemId, String imagePath) {
    }

    private void runReindex(String username, Long companyId, List<ItemImageRef> items) {
        Observation observation = Observation.createNotStarted("norbiz.item_recognition.reindex", observationRegistry)
                .contextualName("item image reindex")
                .highCardinalityKeyValue("company.id", companyId.toString())
                .highCardinalityKeyValue("enduser.id", username)
                .start();
        int indexed = 0;
        int failed = 0;
        try (Observation.Scope ignored = observation.openScope()) {
            for (ItemImageRef ref : items) {
                try {
                    index(companyId, ref.itemId(), ref.imagePath(), readImage(ref.imagePath()));
                    indexed++;
                } catch (ItemRecognitionUnavailableException ex) {
                    // Embedder down: every remaining item would fail the same way.
                    log.warn("Item image reindex for company {} stopped after {} item(s): {}", companyId, indexed, ex.getMessage());
                    observation.error(ex);
                    return;
                } catch (RuntimeException | IOException ex) {
                    failed++;
                    log.warn("Item image for item id={} not indexed: {}", ref.itemId(), ex.getMessage());
                }
            }
            log.info("Item image reindex for company {} finished: {} indexed, {} failed", companyId, indexed, failed);
        } finally {
            observation.highCardinalityKeyValue("items.indexed", Integer.toString(indexed))
                    .highCardinalityKeyValue("items.failed", Integer.toString(failed))
                    .stop();
            reindexing.remove(companyId);
        }
    }

    private void index(long companyId, long itemId, String imagePath, byte[] image) {
        ImageEmbeddingClient.Embedding embedding = embeddingClient.embed(image);
        transactionTemplate.executeWithoutResult(status ->
                embeddingRepository.replaceForItem(companyId, itemId, imagePath, embedding.model(), embedding.vector()));
        log.info("Indexed image '{}' of item id={} for recognition (model={})", imagePath, itemId, embedding.model());
    }

    private byte[] readImage(String imagePath) throws IOException {
        Path file = uploadRoot.resolve(imagePath).normalize();
        if (!file.startsWith(uploadRoot)) {
            throw new SecurityException("Invalid image path: " + imagePath);
        }
        return Files.readAllBytes(file);
    }

    // In its own short transaction (User.companies is lazy) since the class isn't transactional.
    private void assertCompanyAccess(String username, Long companyId) {
        Boolean hasAccess = transactionTemplate.execute(status -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
            return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"))
                    || user.getCompanies().stream().anyMatch(c -> c.getId().equals(companyId));
        });
        if (!Boolean.TRUE.equals(hasAccess)) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
