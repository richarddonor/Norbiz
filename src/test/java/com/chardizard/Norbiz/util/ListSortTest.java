package com.chardizard.Norbiz.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListSortTest {

    private static final Map<String, String> SORTABLE = ListSort.fields("company", "company.name", "date", "deliveryDate");

    @Test
    void unsortedRequestGetsTheDefaultThenId() {
        Pageable sorted = ListSort.apply(PageRequest.of(2, 50), SORTABLE, ListSort.CREATED_FIRST);

        assertThat(sorted.getPageNumber()).isEqualTo(2);
        assertThat(sorted.getPageSize()).isEqualTo(50);
        assertThat(sorted.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void columnKeysMapToEntityPathsAheadOfTheDefault() {
        Pageable request = PageRequest.of(0, 25, Sort.by(Sort.Order.asc("company"), Sort.Order.desc("date")));

        Pageable sorted = ListSort.apply(request, SORTABLE, ListSort.UPDATED_FIRST);

        assertThat(sorted.getSort()).containsExactly(
                Sort.Order.asc("company.name"), Sort.Order.desc("deliveryDate"),
                Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
    }

    @Test
    void unknownColumnIsRejected() {
        Pageable request = PageRequest.of(0, 25, Sort.by("costPrice"));

        assertThatThrownBy(() -> ListSort.apply(request, SORTABLE, ListSort.CREATED_FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costPrice");
    }

    @Test
    void unpagedKeepsTheSort() {
        Pageable sorted = ListSort.apply(PageRequest.of(3, 50, Sort.by("date")), SORTABLE, ListSort.CREATED_FIRST);

        Pageable all = ListSort.unpaged(sorted);

        assertThat(all.isPaged()).isFalse();
        assertThat(all.getSort()).isEqualTo(sorted.getSort());
    }
}
