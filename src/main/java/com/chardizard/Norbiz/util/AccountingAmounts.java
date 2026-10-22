package com.chardizard.Norbiz.util;

import com.chardizard.Norbiz.models.PurchaseInvoice;
import com.chardizard.Norbiz.models.PurchaseInvoiceFee;
import com.chardizard.Norbiz.models.PurchaseInvoiceLine;
import com.chardizard.Norbiz.models.SalesInvoice;
import com.chardizard.Norbiz.models.SalesInvoiceLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Money math shared by the AR/AP module. Open items are kept to 2 decimals; invoice totals are computed
 * unrounded from the lines and rounded HALF_UP once, when the invoice posts (and stored), so every screen,
 * report and allocation uses the same figure.
 */
public final class AccountingAmounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AccountingAmounts() {
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** quantity * price * (1 - discount% / 100), unrounded. */
    public static BigDecimal lineNet(BigDecimal quantity, BigDecimal price, BigDecimal discountPercentage) {
        BigDecimal gross = quantity.multiply(price);
        return gross.subtract(gross.multiply(discountPercentage).divide(HUNDRED));
    }

    /** subtotal * discount% / 100, unrounded. */
    public static BigDecimal discount(BigDecimal subtotal, BigDecimal discountPercentage) {
        return subtotal.multiply(discountPercentage).divide(HUNDRED);
    }

    /** Net payable: items less the header discount, plus fees — rounded to 2 decimals. */
    public static BigDecimal purchaseInvoiceNet(PurchaseInvoice invoice) {
        BigDecimal items = BigDecimal.ZERO;
        for (PurchaseInvoiceLine line : invoice.getLines()) {
            items = items.add(lineNet(line.getQuantity(), line.getCostPrice(), line.getDiscountPercentage()));
        }
        BigDecimal fees = invoice.getFees().stream().map(PurchaseInvoiceFee::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return money(items.subtract(discount(items, invoice.getDiscountPercentage())).add(fees));
    }

    /** Net receivable: items less the header discount — rounded to 2 decimals. */
    public static BigDecimal salesInvoiceNet(SalesInvoice invoice) {
        BigDecimal items = BigDecimal.ZERO;
        for (SalesInvoiceLine line : invoice.getLines()) {
            items = items.add(lineNet(line.getQuantity(), line.getUnitPrice(), line.getDiscountPercentage()));
        }
        return money(items.subtract(discount(items, invoice.getDiscountPercentage())));
    }

    /** documentDate + termDays (both UTC-midnight instants). */
    public static Instant dueDate(Instant documentDate, int termDays) {
        return documentDate.plus(termDays, ChronoUnit.DAYS);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
