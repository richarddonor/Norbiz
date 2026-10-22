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
 * Read-through Redis cache for paginated list/lookup results and TTL-only snapshots (see docs/CACHING.md).
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
    private static final String KEY_PREFIX = "norbiz:c:v2:";

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
        JavaType type = mapper.getTypeFactory().constructParametricType(CachedPage.class, elementType);
        CachedPage<T> page = readThrough(region.name(),
                () -> key(region, scope, generations.current(region, scope), params, pageable),
                type, ttl(region), () -> CachedPage.of(loader.get()));
        return page.toPage(pageable);
    }

    /**
     * Read-through cache for one computed value that is <b>not</b> invalidated by writes — it simply expires
     * after {@code ttl}. For aggregates over high-churn data (dashboard widgets), where generation counters
     * would invalidate on nearly every posting and callers accept a result up to {@code ttl} old.
     * {@code name} is a low-cardinality label (it becomes the {@code cache.region} metric tag).
     */
    public <T> T snapshot(String name, Long companyId, Map<String, ?> params, Duration ttl,
                          Class<T> type, Supplier<T> loader) {
        if (!properties.isEnabled()) return loader.get();
        return readThrough(name, () -> snapshotKey(name, companyId, params),
                mapper.getTypeFactory().constructType(type), ttl, loader);
    }

    private <S> S readThrough(String region, Supplier<String> keyFn, JavaType type, Duration ttl, Supplier<S> loader) {
        Observation observation = Observation.createNotStarted("norbiz.cache", observationRegistry)
                .contextualName("cache " + region.toLowerCase())
                .lowCardinalityKeyValue("cache.region", region);
        observation.start();
        String result = "error";
        try (Observation.Scope ignored = observation.openScope()) {
            String key;
            String cached;
            try {
                key = keyFn.get();
                observation.highCardinalityKeyValue("cache.key", key);
                cached = redis.opsForValue().get(key);
            } catch (RuntimeException ex) {
                log.warn("Cache read failed for {} — serving from database: {}", region, ex.getMessage());
                return loader.get();
            }

            if (cached != null) {
                try {
                    S hit = mapper.readValue(cached, type);
                    result = "hit";
                    log.debug("Cache hit {} {}", region, key);
                    return hit;
                } catch (RuntimeException ex) {
                    // Unreadable entry (e.g. a DTO changed shape): treat as a miss and overwrite it.
                    log.warn("Discarding unreadable cache entry {}: {}", key, ex.getMessage());
                }
            }

            S value = loader.get();
            result = "miss";
            log.debug("Cache miss {} {}", region, key);
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
            } catch (RuntimeException ex) {
                log.warn("Cache write failed for {}: {}", key, ex.getMessage());
            }
            return value;
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

    static String snapshotKey(String name, Long companyId, Map<String, ?> params) {
        return KEY_PREFIX + "SNAPSHOT_" + name + ":" + sha256("company:" + companyId + "|" + canonical(params));
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
