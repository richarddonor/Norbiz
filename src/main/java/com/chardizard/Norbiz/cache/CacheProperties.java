package com.chardizard.Norbiz.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.cache.*} — see docs/CACHING.md. TTLs only bound memory; correctness comes from the
 * generation counters bumped on commit, so a long TTL never means stale reads.
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
        private Duration list = Duration.ofMinutes(2);
    }
}
