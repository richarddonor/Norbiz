package com.chardizard.Norbiz.cache;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Read-through Redis cache for paginated list/lookup results (see docs/CACHING.md).
 *
 * Callers must do their authorization and company-access checks <b>before</b> calling {@link #page},
 * and must put everything that changes the response shape into {@code scope}/{@code params}
 * (e.g. {@code canViewCostPrice}) — the cache itself knows nothing about permissions.
 *
 * Redis problems never fail a request: any Redis or (de)serialization error falls through to the
 * loader and is logged at WARN.
 */
@Component
public class QueryCache {

    private static final Logger log = LoggerFactory.getLogger(QueryCache.class);
    // Bump when the stored format or a cached DTO changes incompatibly, to orphan old entries on deploy.
    private static final String KEY_PREFIX = "norbiz:c:v1:";

    private final StringRedisTemplate redis;
    private final GenerationStore generations;
    private final CacheProperties properties;
    private final ObservationRegistry observationRegistry;
    // Private mapper so spring.jackson.* tweaks to the HTTP mapper can't break stored entries.
    private final JsonMapper mapper = JsonMapper.builder().build();

    public QueryCache(StringRedisTemplate redis, GenerationStore generations, CacheProperties properties,
                      ObservationRegistry observationRegistry) {
        this.redis = redis;
        this.generations = generations;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
    }

    public <T> Page<T> page(CacheRegion region, CacheScope scope, Map<String, ?> params, Pageable pageable,
                            Class<T> elementType, Supplier<Page<T>> loader) {
        if (!properties.isEnabled()) return loader.get();

        Observation observation = Observation.createNotStarted("norbiz.cache", observationRegistry)
                .contextualName("cache " + region.name().toLowerCase())
                .lowCardinalityKeyValue("cache.region", region.name());
        observation.start();
        String result = "error";
        try (Observation.Scope ignored = observation.openScope()) {
            JavaType type = mapper.getTypeFactory().constructParametricType(CachedPage.class, elementType);

            String key;
            String cached;
            try {
                key = key(region, scope, generations.current(region, scope), params, pageable);
                observation.highCardinalityKeyValue("cache.key", key);
                cached = redis.opsForValue().get(key);
            } catch (RuntimeException ex) {
                log.warn("Cache read failed for {} — serving from database: {}", region, ex.getMessage());
                return loader.get();
            }

            if (cached != null) {
                try {
                    CachedPage<T> hit = mapper.readValue(cached, type);
                    result = "hit";
                    log.debug("Cache hit {} {}", region, key);
                    return hit.toPage(pageable);
                } catch (RuntimeException ex) {
                    // Unreadable entry (e.g. a DTO changed shape): treat as a miss and overwrite it.
                    log.warn("Discarding unreadable cache entry {}: {}", key, ex.getMessage());
                }
            }

            Page<T> page = loader.get();
            result = "miss";
            log.debug("Cache miss {} {}", region, key);
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(CachedPage.of(page)), ttl(region));
            } catch (RuntimeException ex) {
                log.warn("Cache write failed for {}: {}", key, ex.getMessage());
            }
            return page;
        } catch (RuntimeException ex) {
            observation.error(ex);
            throw ex;
        } finally {
            observation.lowCardinalityKeyValue("cache.result", result);
            observation.stop();
        }
    }

    /** Builds a params map from alternating key/value pairs; unlike Map.of it accepts null values (dropped from the key). */
    public static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private Duration ttl(CacheRegion region) {
        return region.kind() == CacheRegion.Kind.LOOKUP ? properties.getTtl().getLookup() : properties.getTtl().getList();
    }

    static String key(CacheRegion region, CacheScope scope, List<String> generations,
                      Map<String, ?> params, Pageable pageable) {
        TreeMap<String, Object> canonical = canonical(params);
        if (pageable.isPaged()) {
            canonical.put("~page", pageable.getPageNumber());
            canonical.put("~size", pageable.getPageSize());
        }
        canonical.put("~sort", pageable.getSort().toString());
        String material = scope + "|" + generations + "|" + canonical;
        return KEY_PREFIX + region.name() + ":" + sha256(material);
    }

    // Sorted, null-free and recursively sorted for nested maps (e.g. a controller's filters map),
    // so logically equal requests always produce the same key.
    private static TreeMap<String, Object> canonical(Map<String, ?> params) {
        TreeMap<String, Object> out = new TreeMap<>();
        params.forEach((k, v) -> {
            if (v == null) return;
            out.put(k, v instanceof Map<?, ?> m ? canonical(stringKeys(m)) : String.valueOf(v));
        });
        return out;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        TreeMap<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
