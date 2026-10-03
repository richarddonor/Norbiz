package com.chardizard.Norbiz.cache;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** The Redis representation of a {@link Page}: just enough to rebuild an equivalent PageImpl. */
record CachedPage<T>(List<T> content, long totalElements) {

    static <T> CachedPage<T> of(Page<T> page) {
        return new CachedPage<>(page.getContent(), page.getTotalElements());
    }

    Page<T> toPage(Pageable pageable) {
        return pageable.isPaged() ? new PageImpl<>(content, pageable, totalElements) : new PageImpl<>(content);
    }
}
