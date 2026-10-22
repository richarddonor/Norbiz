package com.chardizard.Norbiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
public class PageResponse<T> {
    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean last;
    /** Grand totals of the amount/quantity columns over every row matching the filters, keyed by
     * column key — only when the request asked for {@code totals=true} (see {@code ListTotals}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, BigDecimal> totals;
    /** Set (true) instead of {@code totals} when more rows match than a Java-computed total maps
     * ({@code ListTotals.LIMIT}) — the frontend asks the user to narrow the filters. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean totalsTooMany;

    private PageResponse(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
    }

    // Page numbers are exposed 1-indexed to match the request's ?page= convention
    // (spring.data.web.pageable.one-indexed-parameters=true), even though Spring's
    // underlying Page/Pageable are 0-indexed internally.
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber() + 1, page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    public PageResponse<T> withTotals(Map<String, BigDecimal> totals) {
        this.totals = totals;
        return this;
    }

    /** {@code ListTotals.within}'s result: the totals, or the too-many flag when it was over the limit. */
    public PageResponse<T> withTotals(Optional<Map<String, BigDecimal>> totals) {
        totals.ifPresentOrElse(this::withTotals, () -> this.totalsTooMany = true);
        return this;
    }
}
