package com.chardizard.Norbiz.util;

import com.chardizard.Norbiz.services.InventoryBalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.core.PropertyPath;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Every list endpoint's ?sort= whitelist (a SORTABLE map, see ListSort) must name real entity paths —
// a typo would only surface as a 500 when someone clicks that column header.
class SortableFieldsTest {

    @Test
    void everySortablePathResolvesOnItsEntity() throws Exception {
        List<String> checked = new ArrayList<>();
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/chardizard/Norbiz/controllers/*Controller.class");
        for (var resource : resources) {
            String simple = resource.getFilename().replace(".class", "");
            Class<?> controller = Class.forName("com.chardizard.Norbiz.controllers." + simple);
            Map<String, String> sortable = sortable(controller);
            if (sortable == null) continue;
            Class<?> entity = Class.forName("com.chardizard.Norbiz.models." + simple.replace("Controller", ""));
            sortable.values().forEach(path -> PropertyPath.from(path, entity));
            checked.add(simple);
        }
        PropertyPath.from("updatedAt", Class.forName("com.chardizard.Norbiz.models.InventoryBalance"));
        sortable(InventoryBalanceService.class).values()
                .forEach(path -> PropertyPath.from(path, com.chardizard.Norbiz.models.InventoryBalance.class));

        assertThat(checked).hasSizeGreaterThanOrEqualTo(28);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> sortable(Class<?> type) throws IllegalAccessException {
        try {
            Field field = type.getDeclaredField("SORTABLE");
            field.setAccessible(true);
            return (Map<String, String>) field.get(null);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
