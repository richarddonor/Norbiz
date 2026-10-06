package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.exceptions.InsufficientStockException;
import com.chardizard.Norbiz.models.InventoryBalance;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.models.Warehouse;
import com.chardizard.Norbiz.repositories.InventoryBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single place running {@link InventoryBalance}s are changed, and the home of the
 * "on-hand stock can never go below zero" business rule (docs/INVENTORY.md "Negative stock").
 * Every transaction service posts its balance deltas through {@link #apply}; services whose
 * posting takes stock out of a warehouse also call {@link #assertAvailable} first, so the caller
 * gets every short item in one error — and, on create, before a reference number is burned.
 *
 * Both methods must run inside the caller's transaction: they take row locks on the balances
 * they touch, which serialize concurrent postings against the same item/warehouse until commit.
 */
@Service
@RequiredArgsConstructor
public class InventoryStockService {

    private static final Logger log = LoggerFactory.getLogger(InventoryStockService.class);

    private final InventoryBalanceRepository inventoryBalanceRepository;

    /**
     * Checks that {@code warehouse} holds at least the given on-hand quantity of each item.
     * {@code outgoing} maps each line's item to the quantity it takes out (positive values; lines
     * with zero or negative values are ignored, and repeated items are summed). Throws
     * {@link InsufficientStockException} listing every item that falls short.
     */
    public <L> void assertAvailable(Warehouse warehouse, List<L> lines, Function<L, Item> item, Function<L, BigDecimal> outgoing) {
        Map<Long, Item> items = new LinkedHashMap<>();
        Map<Long, BigDecimal> required = new LinkedHashMap<>();
        for (L line : lines) {
            BigDecimal quantity = outgoing.apply(line);
            if (quantity == null || quantity.signum() <= 0) continue;
            Item i = item.apply(line);
            items.putIfAbsent(i.getId(), i);
            required.merge(i.getId(), quantity, BigDecimal::add);
        }
        if (required.isEmpty()) return;

        Map<Long, BigDecimal> onHand = inventoryBalanceRepository.findForUpdate(warehouse.getId(), required.keySet()).stream()
                .collect(Collectors.toMap(b -> b.getItem().getId(), InventoryBalance::getQuantity));

        List<InsufficientStockException.Shortfall> shortfalls = new ArrayList<>();
        required.forEach((itemId, qty) -> {
            BigDecimal available = onHand.getOrDefault(itemId, BigDecimal.ZERO);
            if (available.compareTo(qty) < 0) {
                shortfalls.add(shortfall(items.get(itemId), warehouse, available, qty));
            }
        });
        if (!shortfalls.isEmpty()) {
            log.warn("Rejected posting to warehouse {}: insufficient stock for {} item(s): {}",
                    warehouse.getId(), shortfalls.size(), shortfalls);
            throw new InsufficientStockException(shortfalls);
        }
    }

    /**
     * Adds the deltas to the item's running balance in the warehouse, creating the balance row on
     * first posting. Rejects a negative {@code quantityDelta} that would leave on-hand below zero;
     * a positive delta is always allowed, even onto a balance that is already negative (legacy
     * data from before this rule), since it only moves it toward zero.
     */
    public InventoryBalance apply(Item item, Warehouse warehouse, BigDecimal quantityDelta, BigDecimal transitQuantityDelta, Instant now) {
        InventoryBalance balance = inventoryBalanceRepository.findForUpdate(item.getId(), warehouse.getId())
                .orElseGet(() -> {
                    InventoryBalance b = new InventoryBalance();
                    b.setItem(item);
                    b.setWarehouse(warehouse);
                    b.setQuantity(BigDecimal.ZERO);
                    b.setTransitQuantity(BigDecimal.ZERO);
                    return b;
                });

        BigDecimal newQuantity = balance.getQuantity().add(quantityDelta);
        if (quantityDelta.signum() < 0 && newQuantity.signum() < 0) {
            InsufficientStockException.Shortfall shortfall = shortfall(item, warehouse, balance.getQuantity(), quantityDelta.negate());
            log.warn("Rejected posting to warehouse {}: insufficient stock: {}", warehouse.getId(), shortfall);
            throw new InsufficientStockException(List.of(shortfall));
        }

        balance.setQuantity(newQuantity);
        balance.setTransitQuantity(balance.getTransitQuantity().add(transitQuantityDelta));
        balance.setUpdatedAt(now);
        return inventoryBalanceRepository.save(balance);
    }

    private static InsufficientStockException.Shortfall shortfall(Item item, Warehouse warehouse, BigDecimal available, BigDecimal required) {
        return new InsufficientStockException.Shortfall(item.getId(), item.getItemCode(), item.getName(),
                warehouse.getId(), warehouse.getName(), available, required);
    }
}
