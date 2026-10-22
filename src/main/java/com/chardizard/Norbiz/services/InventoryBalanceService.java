package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.InventoryBalanceResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.SpecificationUtils;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryBalanceService {

    // Column key → entity path for ?sort= on the live balance (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "warehouse.company.name",
            "warehouse", "warehouse.name",
            "itemCode", "item.itemCode",
            "itemName", "item.name",
            "quantity", "quantity",
            "transitQuantity", "transitQuantity");

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final ItemRepository itemRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemPriceRepository itemPriceRepository;
    private final UserRepository userRepository;

    public Page<InventoryBalanceResponse> findBalances(String username, Long warehouseId, Long itemId,
                                                        Instant asOfDate, Instant rangeStart, Instant rangeEnd,
                                                        boolean canViewCostPrice, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        List<Long> companyIds = null;
        if (!isSuperAdmin) {
            companyIds = user.getCompanies().stream().map(Company::getId).collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
        }

        // As-of and period balances are aggregated from the ledger in memory, in item/warehouse order;
        // only the live running balance (a real table) can be sorted by column.
        boolean liveBalance = !(rangeStart != null && rangeEnd != null) && asOfDate == null;
        if (!liveBalance && pageable.getSort().isSorted()) {
            throw new IllegalArgumentException("Sorting is only available for current balances, not as-of or period reports");
        }

        if (rangeStart != null && rangeEnd != null) {
            return findRange(companyIds, warehouseId, itemId, rangeStart, rangeEnd, canViewCostPrice, pageable);
        }
        return asOfDate != null
                ? findAsOf(companyIds, warehouseId, itemId, asOfDate, canViewCostPrice, pageable)
                : findCurrent(companyIds, warehouseId, itemId, canViewCostPrice, pageable);
    }

    private Page<InventoryBalanceResponse> findCurrent(List<Long> companyIds, Long warehouseId, Long itemId,
                                                        boolean canViewCostPrice, Pageable pageable) {

        Specification<InventoryBalance> spec = currentSpec(companyIds, warehouseId, itemId);
        Page<InventoryBalance> page = inventoryBalanceRepository.findAll(spec, ListSort.apply(pageable, SORTABLE, ListSort.UPDATED_FIRST));

        Map<Long, BigDecimal> costPrices = canViewCostPrice
                ? loadCostPrices(page.getContent().stream().map(b -> b.getItem().getId()).collect(Collectors.toSet()))
                : Map.of();

        return page.map(b -> toResponse(b.getItem(), b.getWarehouse(), b.getQuantity(), b.getTransitQuantity(), costPrices, canViewCostPrice));
    }

    private Specification<InventoryBalance> currentSpec(List<Long> companyIds, Long warehouseId, Long itemId) {
        Specification<InventoryBalance> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("warehouse").get("company").get("id").in(companyIds);
        Specification<InventoryBalance> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<InventoryBalance> itemScope = itemId == null ? null
                : (root, query, cb) -> cb.equal(root.get("item").get("id"), itemId);
        return SpecificationUtils.allOf(companyScope, warehouseScope, itemScope);
    }

    private Page<InventoryBalanceResponse> findAsOf(List<Long> companyIds, Long warehouseId, Long itemId, Instant asOfDate,
                                                     boolean canViewCostPrice, Pageable pageable) {
        List<Object[]> rows = inventoryMovementRepository.aggregateBalanceAsOf(asOfDate, companyIds, warehouseId, itemId);

        int start = pageable.isPaged() ? Math.min((int) pageable.getOffset(), rows.size()) : 0;
        int end = pageable.isPaged() ? Math.min(start + pageable.getPageSize(), rows.size()) : rows.size();
        List<Object[]> pageRows = rows.subList(start, end);

        Set<Long> itemIds = new HashSet<>();
        Set<Long> warehouseIds = new HashSet<>();
        for (Object[] row : pageRows) {
            itemIds.add((Long) row[0]);
            warehouseIds.add((Long) row[1]);
        }

        Map<Long, Item> items = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<Long, Warehouse> warehouses = warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, w -> w));
        Map<Long, BigDecimal> costPrices = canViewCostPrice ? loadCostPrices(itemIds) : Map.of();

        List<InventoryBalanceResponse> content = pageRows.stream()
                .map(row -> toResponse(items.get((Long) row[0]), warehouses.get((Long) row[1]),
                        (BigDecimal) row[2], (BigDecimal) row[3], costPrices, canViewCostPrice))
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, rows.size());
    }

    private Page<InventoryBalanceResponse> findRange(List<Long> companyIds, Long warehouseId, Long itemId,
                                                     Instant rangeStart, Instant rangeEnd,
                                                     boolean canViewCostPrice, Pageable pageable) {
        // Beginning balance = cumulative balance strictly before the period starts,
        // so movements ON rangeStart count as activity during the period, not a
        // pre-existing balance (standard "beginning balance" accounting convention).
        List<Object[]> beginningRows = inventoryMovementRepository.aggregateBalanceAsOf(
                rangeStart.minusMillis(1), companyIds, warehouseId, itemId);
        List<Object[]> endingRows = inventoryMovementRepository.aggregateBalanceAsOf(
                rangeEnd, companyIds, warehouseId, itemId);

        Map<ItemWarehouseKey, BigDecimal[]> beginning = toBalanceMap(beginningRows);
        Map<ItemWarehouseKey, BigDecimal[]> ending = toBalanceMap(endingRows);

        // Every key with beginning-of-period activity also has ending-of-period
        // activity (the ending sum covers a superset of movements), but the union
        // is kept for safety rather than assuming that invariant holds forever.
        Set<ItemWarehouseKey> keySet = new LinkedHashSet<>(beginning.keySet());
        keySet.addAll(ending.keySet());
        List<ItemWarehouseKey> keys = new ArrayList<>(keySet);
        keys.sort(Comparator.comparing(ItemWarehouseKey::itemId).thenComparing(ItemWarehouseKey::warehouseId));

        int start = pageable.isPaged() ? Math.min((int) pageable.getOffset(), keys.size()) : 0;
        int end = pageable.isPaged() ? Math.min(start + pageable.getPageSize(), keys.size()) : keys.size();
        List<ItemWarehouseKey> pageKeys = keys.subList(start, end);

        Set<Long> itemIds = pageKeys.stream().map(ItemWarehouseKey::itemId).collect(Collectors.toSet());
        Set<Long> warehouseIds = pageKeys.stream().map(ItemWarehouseKey::warehouseId).collect(Collectors.toSet());

        Map<Long, Item> items = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<Long, Warehouse> warehouses = warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, w -> w));
        Map<Long, BigDecimal> costPrices = canViewCostPrice ? loadCostPrices(itemIds) : Map.of();

        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO};
        List<InventoryBalanceResponse> content = pageKeys.stream()
                .map(key -> {
                    BigDecimal[] beg = beginning.getOrDefault(key, zero);
                    BigDecimal[] end2 = ending.getOrDefault(key, zero);
                    return toRangeResponse(items.get(key.itemId()), warehouses.get(key.warehouseId()),
                            beg[0], beg[1], end2[0], end2[1], costPrices, canViewCostPrice);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, keys.size());
    }

    private Map<ItemWarehouseKey, BigDecimal[]> toBalanceMap(List<Object[]> rows) {
        Map<ItemWarehouseKey, BigDecimal[]> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(new ItemWarehouseKey((Long) row[0], (Long) row[1]), new BigDecimal[]{(BigDecimal) row[2], (BigDecimal) row[3]});
        }
        return map;
    }

    private InventoryBalanceResponse toRangeResponse(Item item, Warehouse warehouse,
                                                      BigDecimal beginningQty, BigDecimal beginningTransit,
                                                      BigDecimal endingQty, BigDecimal endingTransit,
                                                      Map<Long, BigDecimal> costPrices, boolean canViewCostPrice) {
        InventoryBalanceResponse res = new InventoryBalanceResponse();
        res.setCompanyId(warehouse.getCompany().getId());
        res.setCompanyName(warehouse.getCompany().getName());
        res.setItemId(item.getId());
        res.setItemCode(item.getItemCode());
        res.setItemName(item.getName());
        res.setWarehouseId(warehouse.getId());
        res.setWarehouseName(warehouse.getName());
        res.setBeginningQuantity(beginningQty);
        res.setBeginningTransitQuantity(beginningTransit);
        res.setEndingQuantity(endingQty);
        res.setEndingTransitQuantity(endingTransit);
        res.setNetQuantityChange(endingQty.subtract(beginningQty));
        res.setNetTransitQuantityChange(endingTransit.subtract(beginningTransit));
        if (canViewCostPrice) {
            BigDecimal costPrice = costPrices.get(item.getId());
            res.setCostPrice(costPrice);
            res.setValue(costPrice != null ? endingQty.multiply(costPrice) : null);
        }
        return res;
    }

    private record ItemWarehouseKey(Long itemId, Long warehouseId) {}

    // Postgres caps a statement at 65,535 bind parameters, and totals ask for every item at once.
    private static final int ID_CHUNK = 10_000;

    private Map<Long, BigDecimal> loadCostPrices(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        List<Long> ids = new ArrayList<>(itemIds);
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (int i = 0; i < ids.size(); i += ID_CHUNK) {
            for (ItemPrice p : itemPriceRepository.findByItemIdInAndPriceType(ids.subList(i, Math.min(i + ID_CHUNK, ids.size())), PriceType.COST_PRICE)) {
                prices.put(p.getItem().getId(), p.getAmount());
            }
        }
        return prices;
    }

    /**
     * Grand totals over every balance row {@link #findBalances} would list for the same arguments, keyed by
     * the report's column keys. Summed from the raw quantities (never building responses, so no item or
     * warehouse loads) — it scales to every item × warehouse. {@code value} (quantity, or ending quantity
     * for a period, × cost price) only with {@code canViewCostPrice}.
     */
    public Map<String, BigDecimal> totals(String username, Long warehouseId, Long itemId,
                                          Instant asOfDate, Instant rangeStart, Instant rangeEnd, boolean canViewCostPrice) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Optional<List<Long>> scope = companyScope(username);
        if (scope.isEmpty()) return totals;
        List<Long> companyIds = scope.get().isEmpty() ? null : scope.get();

        // item id → quantity that carries the value (on hand, or ending on hand for a period)
        List<Map.Entry<Long, BigDecimal>> valued = new ArrayList<>();
        if (rangeStart != null && rangeEnd != null) {
            Map<ItemWarehouseKey, BigDecimal[]> beginning = toBalanceMap(inventoryMovementRepository.aggregateBalanceAsOf(
                    rangeStart.minusMillis(1), companyIds, warehouseId, itemId));
            Map<ItemWarehouseKey, BigDecimal[]> ending = toBalanceMap(inventoryMovementRepository.aggregateBalanceAsOf(
                    rangeEnd, companyIds, warehouseId, itemId));
            Set<ItemWarehouseKey> keys = new HashSet<>(beginning.keySet());
            keys.addAll(ending.keySet());
            BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO};
            for (ItemWarehouseKey key : keys) {
                BigDecimal[] beg = beginning.getOrDefault(key, zero);
                BigDecimal[] end = ending.getOrDefault(key, zero);
                ListTotals.add(totals, "beginningQuantity", beg[0]);
                ListTotals.add(totals, "beginningTransitQuantity", beg[1]);
                ListTotals.add(totals, "endingQuantity", end[0]);
                ListTotals.add(totals, "endingTransitQuantity", end[1]);
                ListTotals.add(totals, "netQuantityChange", end[0].subtract(beg[0]));
                ListTotals.add(totals, "netTransitQuantityChange", end[1].subtract(beg[1]));
                valued.add(Map.entry(key.itemId(), end[0]));
            }
        } else if (asOfDate != null) {
            for (Object[] row : inventoryMovementRepository.aggregateBalanceAsOf(asOfDate, companyIds, warehouseId, itemId)) {
                ListTotals.add(totals, "quantity", (BigDecimal) row[2]);
                ListTotals.add(totals, "transitQuantity", (BigDecimal) row[3]);
                valued.add(Map.entry((Long) row[0], (BigDecimal) row[2]));
            }
        } else {
            for (InventoryBalance b : inventoryBalanceRepository.findAll(currentSpec(companyIds, warehouseId, itemId))) {
                ListTotals.add(totals, "quantity", b.getQuantity());
                ListTotals.add(totals, "transitQuantity", b.getTransitQuantity());
                valued.add(Map.entry(b.getItem().getId(), b.getQuantity()));
            }
        }

        if (canViewCostPrice) {
            Map<Long, BigDecimal> costPrices = loadCostPrices(valued.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
            for (Map.Entry<Long, BigDecimal> e : valued) {
                BigDecimal costPrice = costPrices.get(e.getKey());
                if (costPrice != null) ListTotals.add(totals, "value", e.getValue().multiply(costPrice));
            }
        }
        return totals;
    }

    /** The caller's company ids — empty list for SUPER_ADMIN (every company), no value when they belong to none. */
    private Optional<List<Long>> companyScope(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        if (user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"))) return Optional.of(List.of());
        List<Long> companyIds = user.getCompanies().stream().map(Company::getId).collect(Collectors.toList());
        return companyIds.isEmpty() ? Optional.empty() : Optional.of(companyIds);
    }

    private InventoryBalanceResponse toResponse(Item item, Warehouse warehouse, BigDecimal quantity, BigDecimal transitQuantity,
                                                Map<Long, BigDecimal> costPrices, boolean canViewCostPrice) {
        InventoryBalanceResponse res = new InventoryBalanceResponse();
        res.setCompanyId(warehouse.getCompany().getId());
        res.setCompanyName(warehouse.getCompany().getName());
        res.setItemId(item.getId());
        res.setItemCode(item.getItemCode());
        res.setItemName(item.getName());
        res.setWarehouseId(warehouse.getId());
        res.setWarehouseName(warehouse.getName());
        res.setQuantity(quantity);
        res.setTransitQuantity(transitQuantity);
        if (canViewCostPrice) {
            BigDecimal costPrice = costPrices.get(item.getId());
            res.setCostPrice(costPrice);
            res.setValue(costPrice != null ? quantity.multiply(costPrice) : null);
        }
        return res;
    }
}