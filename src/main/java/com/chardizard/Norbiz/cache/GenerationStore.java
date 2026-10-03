package com.chardizard.Norbiz.cache;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Per-(entity, company) generation counters in Redis. A cache key embeds the current value of every
 * counter its region depends on, so bumping a counter makes every older entry unreachable without
 * having to find and delete it — orphans just age out via TTL.
 *
 * Counters are stored without a TTL and Redis must run with a {@code volatile-*} eviction policy:
 * an evicted counter would restart at 0 and could resurrect an old entry built at generation 0.
 */
@Component
@RequiredArgsConstructor
public class GenerationStore {

    private static final Logger log = LoggerFactory.getLogger(GenerationStore.class);
    private static final String PREFIX = "norbiz:gen:";
    static final String ALL = "all";

    private final StringRedisTemplate redis;

    /**
     * Current counter values for {@code region} as seen by {@code scope}, in a stable order — one MGET.
     * Missing counters read as "0". Throws on Redis failure; {@link QueryCache} handles that.
     */
    public List<String> current(CacheRegion region, CacheScope scope) {
        List<String> keys = keysFor(region, scope);
        if (keys.isEmpty()) return List.of();   // a caller with no companies
        List<String> values = redis.opsForValue().multiGet(keys);
        List<String> result = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String v = values == null ? null : values.get(i);
            result.add(v == null ? "0" : v);
        }
        return result;
    }

    static List<String> keysFor(CacheRegion region, CacheScope scope) {
        List<String> keys = new ArrayList<>();
        for (String entity : region.dependsOn()) {
            if (scope.all() || CompanyResolver.GLOBAL_ONLY.contains(entity)) {
                keys.add(key(entity, ALL));
            } else {
                for (Long companyId : scope.companyIds()) {
                    keys.add(key(entity, String.valueOf(companyId)));
                }
            }
        }
        return keys;
    }

    /**
     * Bumps each target's company counter and its entity-wide {@code :all} counter (read by global
     * scopes, e.g. SUPER_ADMIN lists), pipelined. Failures are logged, not thrown: the write already
     * committed, and the worst case is a stale entry until its TTL expires.
     */
    public void bump(Collection<CompanyResolver.Target> targets) {
        if (targets.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (CompanyResolver.Target t : targets) {
            keys.add(key(t.entity(), ALL));
            if (t.companyId() != null) keys.add(key(t.entity(), String.valueOf(t.companyId())));
        }
        List<String> distinct = keys.stream().distinct().toList();
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                incrAll(connection, distinct);
                return null;
            });
            log.debug("Bumped cache generations {}", distinct);
        } catch (RuntimeException ex) {
            log.warn("Failed to bump cache generations {} — cached entries may be stale until TTL: {}", distinct, ex.getMessage());
        }
    }

    private static void incrAll(RedisConnection connection, List<String> keys) {
        for (String k : keys) {
            connection.stringCommands().incr(k.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String key(String entity, String companyId) {
        return PREFIX + entity + ":" + companyId;
    }
}
