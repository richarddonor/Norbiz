package com.chardizard.Norbiz.cache;

import java.util.List;

/**
 * Whose data a cached result covers. It is part of the cache key, so two callers share an entry
 * only when the server would have returned them the same rows. Always built from a company the
 * server has already access-checked (or from the caller's own memberships), never from raw input.
 *
 * @param all        true for SUPER_ADMIN (all companies) and system-wide data (roles, permissions)
 * @param companyIds sorted company ids; empty when {@code all}
 */
public record CacheScope(boolean all, List<Long> companyIds) {

    private static final CacheScope GLOBAL = new CacheScope(true, List.of());

    public static CacheScope global() {
        return GLOBAL;
    }

    public static CacheScope company(Long companyId) {
        return new CacheScope(false, List.of(companyId));
    }

    public static CacheScope companies(List<Long> companyIds) {
        return new CacheScope(false, companyIds.stream().sorted().distinct().toList());
    }

    @Override
    public String toString() {
        return all ? "global" : "companies" + companyIds;
    }
}
