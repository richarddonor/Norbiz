package com.chardizard.Norbiz.exceptions;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when posting (or voiding) an inventory transaction would take an item's on-hand quantity
 * in a warehouse below zero. Mapped to 409 CONFLICT with error code {@link #CODE} by
 * GlobalExceptionHandler; {@link #getShortfalls()} lists every offending item so the frontend can
 * point at the lines that need fixing rather than just the first one.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    public static final String CODE = "INSUFFICIENT_STOCK";

    private final List<Shortfall> shortfalls;

    public InsufficientStockException(List<Shortfall> shortfalls) {
        super(buildMessage(shortfalls));
        this.shortfalls = List.copyOf(shortfalls);
    }

    /**
     * One item that doesn't have enough stock: {@code required} is the total being taken out of the
     * warehouse by the transaction (summed across its lines), {@code available} the current on-hand.
     */
    public record Shortfall(Long itemId, String itemCode, String itemName, Long warehouseId, String warehouseName,
                            BigDecimal available, BigDecimal required) {
    }

    private static String buildMessage(List<Shortfall> shortfalls) {
        String items = shortfalls.stream()
                .map(s -> s.itemCode() + " in " + s.warehouseName() + " (available "
                        + s.available().stripTrailingZeros().toPlainString() + ", required "
                        + s.required().stripTrailingZeros().toPlainString() + ")")
                .collect(Collectors.joining("; "));
        return "Insufficient stock — this would take inventory below zero: " + items;
    }
}
