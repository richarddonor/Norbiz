package com.chardizard.Norbiz.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.cache.*} — see docs/CACHING.md. Lookup/list TTLs only bound memory; correctness comes from the
 * generation counters bumped on commit, so a long TTL never means stale reads. The dashboard TTL is the
 * exception: widget snapshots have no counters, so it is the maximum staleness.
 */
@Getter
@Setter
@ConfigurationProperties("app.cache")
public class CacheProperties {

    /** Master switch. When false every cached call goes straight to the database. */
    private boolean enabled = true;

    private Ttl ttl = new Ttl();

    @Getter
    @Setter
    public static class Ttl {
        private Duration lookup = Duration.ofMinutes(10);
        private Duration list = Duration.ofMinutes(30);
        /** Dashboard widgets aren't invalidated by writes, so this is how stale a widget may be. */
        private Duration dashboard = Duration.ofMinutes(2);
    }
}
