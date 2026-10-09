package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.DashboardWidget;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.DashboardQueries;
import com.chardizard.Norbiz.repositories.DashboardQueries.AdjustmentTotal;
import com.chardizard.Norbiz.repositories.DashboardQueries.BacklogRow;
import com.chardizard.Norbiz.repositories.DashboardQueries.DatedAmount;
import com.chardizard.Norbiz.repositories.DashboardQueries.OutletItemSalesRow;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Data behind the dashboard widgets (see {@link DashboardWidget}). Every widget is scoped to one company and
 * computed live — not cached, like the inventory and detailed reports. Business dates are UTC-midnight instants,
 * and "today" is the caller's {@code asOf} date so aging/periods follow the user's calendar day, not the server's.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private static final int TOP_SLICES = 8;
    private static final int OLDEST_DOCUMENTS = 8;
    private static final int TOP_RANKED = 5;
    private static final int TOP_AGENTS = 10;
    private static final int GRID_OUTLETS = 10;
    private static final int GRID_ITEMS = 8;
    private static final int STOCK_ALERTS = 8;
    private static final int LOW_COVER_DAYS = 7;
    private static final int TOP_REASONS = 5;
    private static final String OTHERS = "Others";

    /** [minDays, maxDays] inclusive; null max = open-ended. */
    private record Bucket(String label, int minDays, Integer maxDays) {
        boolean contains(int age) {
            return age >= minDays && (maxDays == null || age <= maxDays);
        }
    }

    // Deliveries/pull-outs move in days; purchases and payables in months.
    private static final List<Bucket> SHORT_AGING = List.of(
            new Bucket("0-3 days", 0, 3), new Bucket("4-7 days", 4, 7), new Bucket("8-14 days", 8, 14),
            new Bucket("15-30 days", 15, 30), new Bucket("31+ days", 31, null));
    private static final List<Bucket> LONG_AGING = List.of(
            new Bucket("0-30 days", 0, 30), new Bucket("31-60 days", 31, 60), new Bucket("61-90 days", 61, 90),
            new Bucket("91-180 days", 91, 180), new Bucket("181+ days", 181, null));

    /** What a backlog's slices are ranked (and its "Others" folded) by. */
    private enum Rank { QUANTITY, AMOUNT, COUNT }

    private final DashboardQueries queries;
    private final UserRepository userRepository;

    /** The widgets the caller may pin: those whose VIEW_DASHBOARD_ permission they hold. */
    public List<DashboardWidgetResponse> catalog(Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return Arrays.stream(DashboardWidget.values())
                .filter(w -> authorities.contains(w.getPermission()))
                .map(w -> new DashboardWidgetResponse(w.getSlug(), w.getDisplayName(), w.getCategory(), w.getDescription()))
                .toList();
    }

    // ---- backlog widgets --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BacklogResponse pendingOutletReceives(String username, Long companyId, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.PENDING_OUTLET_RECEIVES);
        return backlog(queries.pendingOutletReceives(companyId), today, SHORT_AGING, Rank.QUANTITY, true, true, true);
    }

    /** Amounts are cost-based, so null without VIEW_COST_PRICE. Breakdown = destination warehouse. */
    @Transactional(readOnly = true)
    public BacklogResponse pendingPurchaseOrders(String username, Long companyId, LocalDate asOf, boolean canViewCostPrice) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.PENDING_PURCHASE_ORDERS);
        return backlog(queries.pendingPurchaseOrders(companyId), today, LONG_AGING, Rank.QUANTITY, true, canViewCostPrice, true);
    }

    /** Breakdown = pull-out reason. Amounts use the pull-out line's selling price (not gated). */
    @Transactional(readOnly = true)
    public BacklogResponse pullOutsAwaitingReceive(String username, Long companyId, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.PULL_OUTS_AWAITING_RECEIVE);
        return backlog(queries.pullOutsAwaitingReceive(companyId), today, SHORT_AGING, Rank.QUANTITY, true, true, true);
    }

    /**
     * Net payable per invoice = discounted line subtotal × (1 − header discount %) + fees, the same figure the
     * invoice response computes. Cost-based, so amounts are null without VIEW_COST_PRICE and slices rank by count.
     * Breakdown = payment status. No quantity.
     */
    @Transactional(readOnly = true)
    public BacklogResponse unpaidPurchaseInvoices(String username, Long companyId, LocalDate asOf, boolean canViewCostPrice) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.UNPAID_PURCHASE_INVOICES);
        Map<Long, BigDecimal> fees = queries.unpaidPurchaseInvoiceFees(companyId).stream()
                .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> nz((BigDecimal) r[1])));
        List<BacklogRow> rows = queries.unpaidPurchaseInvoices(companyId).stream()
                .map(i -> {
                    BigDecimal net = i.lineSubtotal()
                            .multiply(BigDecimal.ONE.subtract(nz(i.headerDiscountPercentage()).movePointLeft(2)))
                            .add(fees.getOrDefault(i.id(), BigDecimal.ZERO))
                            .setScale(2, RoundingMode.HALF_UP);
                    return new BacklogRow(i.id(), i.referenceNumber(), i.date(), i.supplierId(), i.supplierName(),
                            statusLabel(i.paymentStatus()), null, null, net);
                })
                .toList();
        return backlog(rows, today, LONG_AGING, canViewCostPrice ? Rank.AMOUNT : Rank.COUNT, false, canViewCostPrice, true);
    }

    private BacklogResponse backlog(List<BacklogRow> rows, LocalDate today, List<Bucket> buckets, Rank rank,
                                    boolean hasQuantity, boolean amountVisible, boolean hasProgress) {
        Function<Collection<BacklogRow>, BigDecimal> qty = list -> hasQuantity ? sum(list, BacklogRow::outstandingQuantity) : null;
        Function<Collection<BacklogRow>, BigDecimal> amount = list -> amountVisible ? sum(list, BacklogRow::outstandingAmount) : null;

        BigDecimal progress = null;
        if (hasQuantity && hasProgress) {
            BigDecimal total = sum(rows, BacklogRow::quantity);
            BigDecimal outstanding = sum(rows, BacklogRow::outstandingQuantity);
            progress = total.signum() == 0 ? BigDecimal.ZERO
                    : total.subtract(outstanding).multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
        }

        List<BacklogResponse.AgingBucket> aging = buckets.stream().map(b -> {
            List<BacklogRow> in = rows.stream().filter(r -> b.contains(ageDays(r.date(), today))).toList();
            return new BacklogResponse.AgingBucket(b.label(), b.minDays(), b.maxDays(), in.size(), qty.apply(in), amount.apply(in));
        }).toList();

        Map<Long, List<BacklogRow>> byCounterpartyId = rows.stream().collect(Collectors.groupingBy(BacklogRow::counterpartyId));
        List<BacklogResponse.Slice> byCounterparty = topSlices(byCounterpartyId.values().stream()
                .map(list -> new BacklogResponse.Slice(list.getFirst().counterpartyId(), list.getFirst().counterpartyName(),
                        list.size(), qty.apply(list), amount.apply(list)))
                .toList(), rank);

        List<BacklogResponse.Slice> breakdown = topSlices(rows.stream()
                .collect(Collectors.groupingBy(r -> r.group() != null ? r.group() : "(none)"))
                .entrySet().stream()
                .map(e -> new BacklogResponse.Slice(null, e.getKey(), e.getValue().size(), qty.apply(e.getValue()), amount.apply(e.getValue())))
                .toList(), rank);
        if (breakdown.size() == 1 && rows.stream().allMatch(r -> r.group() == null)) breakdown = List.of();

        List<BacklogResponse.Document> oldest = rows.stream()
                .sorted(Comparator.comparing(BacklogRow::date).thenComparing(BacklogRow::id))
                .limit(OLDEST_DOCUMENTS)
                .map(r -> new BacklogResponse.Document(r.id(), r.referenceNumber(), r.date(), ageDays(r.date(), today),
                        r.counterpartyId(), r.counterpartyName(), r.group(),
                        hasQuantity ? r.quantity() : null, hasQuantity ? r.outstandingQuantity() : null,
                        amountVisible ? r.outstandingAmount() : null))
                .toList();

        return new BacklogResponse(rows.size(), byCounterpartyId.size(), qty.apply(rows), amount.apply(rows), progress,
                oldest.isEmpty() ? null : oldest.getFirst().ageDays(), startOfDay(today),
                aging, byCounterparty, breakdown, oldest);
    }

    /** Sorted by the rank measure, top {@link #TOP_SLICES} plus one "Others (n)" slice with a null id. */
    private static List<BacklogResponse.Slice> topSlices(List<BacklogResponse.Slice> slices, Rank rank) {
        Comparator<BacklogResponse.Slice> by = switch (rank) {
            case QUANTITY -> Comparator.comparing(s -> nz(s.quantity()));
            case AMOUNT -> Comparator.comparing(s -> nz(s.amount()));
            case COUNT -> Comparator.comparingLong(BacklogResponse.Slice::documentCount);
        };
        List<BacklogResponse.Slice> sorted = slices.stream()
                .sorted(by.reversed().thenComparing(BacklogResponse.Slice::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (sorted.size() <= TOP_SLICES) return sorted;
        List<BacklogResponse.Slice> rest = sorted.subList(TOP_SLICES, sorted.size());
        List<BacklogResponse.Slice> result = new ArrayList<>(sorted.subList(0, TOP_SLICES));
        result.add(new BacklogResponse.Slice(null, OTHERS + " (" + rest.size() + ")",
                rest.stream().mapToLong(BacklogResponse.Slice::documentCount).sum(),
                sumNullable(rest, BacklogResponse.Slice::quantity), sumNullable(rest, BacklogResponse.Slice::amount)));
        return result;
    }

    private static String statusLabel(String paymentStatus) {
        return switch (paymentStatus) {
            case "UNPAID" -> "Unpaid";
            case "PARTIALLY_PAID" -> "Partially paid";
            default -> paymentStatus;
        };
    }

    // ---- stock ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StockInTransitResponse stockInTransit(String username, Long companyId) {
        begin(username, companyId, null, DashboardWidget.STOCK_IN_TRANSIT);
        List<DashboardQueries.WarehouseStockRow> rows = queries.stockByWarehouse(companyId);

        Function<DashboardQueries.WarehouseStockRow, String> kind = w -> w.main() ? "MAIN" : w.outlet() ? "OUTLET" : "OTHER";
        List<StockInTransitResponse.KindTotal> byKind = List.of("MAIN", "OUTLET", "OTHER").stream()
                .map(k -> {
                    List<DashboardQueries.WarehouseStockRow> in = rows.stream().filter(w -> kind.apply(w).equals(k)).toList();
                    return new StockInTransitResponse.KindTotal(k, in.size(),
                            sum(in, DashboardQueries.WarehouseStockRow::transit), sum(in, DashboardQueries.WarehouseStockRow::onHand));
                })
                .filter(k -> k.warehouseCount() > 0)
                .toList();

        List<DashboardQueries.WarehouseStockRow> withTransit = rows.stream()
                .filter(w -> nz(w.transit()).signum() != 0)
                .sorted(Comparator.comparing((DashboardQueries.WarehouseStockRow w) -> nz(w.transit()).abs()).reversed())
                .toList();
        List<StockInTransitResponse.WarehouseTotal> warehouses = new ArrayList<>(withTransit.stream().limit(TOP_SLICES)
                .map(w -> new StockInTransitResponse.WarehouseTotal(w.id(), w.name(), kind.apply(w), w.itemsInTransit(), nz(w.transit()), nz(w.onHand())))
                .toList());
        if (withTransit.size() > TOP_SLICES) {
            List<DashboardQueries.WarehouseStockRow> rest = withTransit.subList(TOP_SLICES, withTransit.size());
            warehouses.add(new StockInTransitResponse.WarehouseTotal(null, OTHERS + " (" + rest.size() + ")", null,
                    rest.stream().mapToLong(DashboardQueries.WarehouseStockRow::itemsInTransit).sum(),
                    sum(rest, DashboardQueries.WarehouseStockRow::transit), sum(rest, DashboardQueries.WarehouseStockRow::onHand)));
        }

        return new StockInTransitResponse(sum(rows, DashboardQueries.WarehouseStockRow::transit),
                sum(rows, DashboardQueries.WarehouseStockRow::onHand), withTransit.size(),
                queries.distinctItemsInTransit(companyId), byKind, warehouses);
    }

    @Transactional(readOnly = true)
    public OutletStockHealthResponse outletStockHealth(String username, Long companyId, int days, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.OUTLET_STOCK_HEALTH);
        Period period = period(today, days);
        List<OutletItemSalesRow> sales = queries.outletItemSales(companyId, period.from(), period.to());

        Set<Long> warehouseIds = sales.stream().map(OutletItemSalesRow::warehouseId).collect(Collectors.toSet());
        Map<String, BigDecimal> onHand = new HashMap<>();
        for (Object[] b : queries.onHand(warehouseIds)) {
            onHand.put(b[0] + ":" + b[1], nz((BigDecimal) b[2]));
        }
        BigDecimal dayCount = BigDecimal.valueOf(days);

        // Every outlet × item pair the outlet actually sold in the period.
        record Pair(OutletItemSalesRow sale, BigDecimal onHand, BigDecimal cover) {
        }
        List<Pair> pairs = sales.stream().map(s -> {
            BigDecimal qty = onHand.getOrDefault(s.warehouseId() + ":" + s.itemId(), BigDecimal.ZERO);
            return new Pair(s, qty, cover(qty, s.sold(), dayCount));
        }).toList();
        long stockOuts = pairs.stream().filter(p -> p.onHand().signum() <= 0).count();
        long lowCover = pairs.stream().filter(p -> p.onHand().signum() > 0 && p.cover() != null
                && p.cover().compareTo(BigDecimal.valueOf(LOW_COVER_DAYS)) < 0).count();

        List<OutletStockHealthResponse.Ref> outlets = topBySold(sales, OutletItemSalesRow::outletId, GRID_OUTLETS).stream()
                .map(s -> new OutletStockHealthResponse.Ref(s.outletId(), s.outletCode(), s.outletName())).toList();
        List<OutletStockHealthResponse.Ref> items = topBySold(sales, OutletItemSalesRow::itemId, GRID_ITEMS).stream()
                .map(s -> new OutletStockHealthResponse.Ref(s.itemId(), s.itemCode(), s.itemName())).toList();

        Map<Long, Long> warehouseOf = sales.stream()
                .collect(Collectors.toMap(OutletItemSalesRow::outletId, OutletItemSalesRow::warehouseId, (a, b) -> a));
        Map<String, BigDecimal> soldByPair = sales.stream()
                .collect(Collectors.toMap(s -> s.outletId() + ":" + s.itemId(), OutletItemSalesRow::sold, BigDecimal::add));
        List<OutletStockHealthResponse.Cell> cells = new ArrayList<>();
        for (OutletStockHealthResponse.Ref outlet : outlets) {
            for (OutletStockHealthResponse.Ref item : items) {
                BigDecimal qty = onHand.getOrDefault(warehouseOf.get(outlet.id()) + ":" + item.id(), BigDecimal.ZERO);
                BigDecimal sold = soldByPair.getOrDefault(outlet.id() + ":" + item.id(), BigDecimal.ZERO);
                cells.add(new OutletStockHealthResponse.Cell(outlet.id(), item.id(), qty, sold, cover(qty, sold, dayCount)));
            }
        }

        // Stock-outs of the fastest sellers first, then the thinnest cover.
        List<OutletStockHealthResponse.Alert> alerts = pairs.stream()
                .filter(p -> p.onHand().signum() <= 0 || (p.cover() != null && p.cover().compareTo(BigDecimal.valueOf(LOW_COVER_DAYS)) < 0))
                .sorted(Comparator.comparing((Pair p) -> p.onHand().signum() > 0)
                        .thenComparing(p -> p.onHand().signum() > 0 ? p.cover() : p.sale().sold().negate()))
                .limit(STOCK_ALERTS)
                .map(p -> new OutletStockHealthResponse.Alert(p.sale().outletId(), p.sale().outletName(), p.sale().itemId(),
                        p.sale().itemCode(), p.sale().itemName(), p.onHand(), p.sale().sold(), p.cover()))
                .toList();

        return new OutletStockHealthResponse(days, period.from(), startOfDay(today), pairs.size(), stockOuts, lowCover,
                LOW_COVER_DAYS, outlets, items, cells, alerts);
    }

    /** One representative row per key (outlet or item), highest total units sold first. */
    private static List<OutletItemSalesRow> topBySold(List<OutletItemSalesRow> sales, Function<OutletItemSalesRow, Long> key, int limit) {
        Map<Long, BigDecimal> totals = sales.stream().collect(Collectors.toMap(key, OutletItemSalesRow::sold, BigDecimal::add));
        Map<Long, OutletItemSalesRow> sample = sales.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
        return totals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(limit)
                .map(e -> sample.get(e.getKey()))
                .toList();
    }

    private static BigDecimal cover(BigDecimal onHand, BigDecimal sold, BigDecimal days) {
        if (sold == null || sold.signum() <= 0) return null;
        if (onHand.signum() <= 0) return BigDecimal.ZERO;
        return onHand.multiply(days).divide(sold, 1, RoundingMode.HALF_UP);
    }

    // ---- inventory adjustments --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InventoryAdjustmentTrendResponse inventoryAdjustmentTrend(String username, Long companyId, int days, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.INVENTORY_ADJUSTMENT_TREND);
        Period period = period(today, days);
        Map<Instant, AdjustmentTotal> byDay = queries.adjustmentsByDay(companyId, period.from(), period.to()).stream()
                .collect(Collectors.toMap(AdjustmentTotal::date, Function.identity()));

        List<InventoryAdjustmentTrendResponse.DailyPoint> daily = period.days().stream().map(d -> {
            AdjustmentTotal t = byDay.get(d);
            return t == null ? new InventoryAdjustmentTrendResponse.DailyPoint(d, BigDecimal.ZERO, BigDecimal.ZERO, 0)
                    : new InventoryAdjustmentTrendResponse.DailyPoint(d, nz(t.added()), nz(t.removed()), t.documents());
        }).toList();

        List<InventoryAdjustmentTrendResponse.WarehouseTotal> byWarehouse = queries.adjustmentsByWarehouse(companyId, period.from(), period.to()).stream()
                .sorted(Comparator.comparing((AdjustmentTotal t) -> nz(t.added()).add(nz(t.removed()))).reversed())
                .limit(TOP_RANKED)
                .map(t -> new InventoryAdjustmentTrendResponse.WarehouseTotal(t.warehouseId(), t.warehouseName(), nz(t.added()), nz(t.removed())))
                .toList();

        List<InventoryAdjustmentTrendResponse.ReasonCount> reasons = queries.topAdjustmentReasons(companyId, period.from(), period.to(), TOP_REASONS).stream()
                .map(r -> new InventoryAdjustmentTrendResponse.ReasonCount((String) r[0], ((Number) r[1]).longValue()))
                .toList();

        return new InventoryAdjustmentTrendResponse(days, period.from(), startOfDay(today),
                sum(daily, InventoryAdjustmentTrendResponse.DailyPoint::added),
                sum(daily, InventoryAdjustmentTrendResponse.DailyPoint::removed),
                daily.stream().mapToLong(InventoryAdjustmentTrendResponse.DailyPoint::documents).sum(),
                daily, byWarehouse, reasons);
    }

    // ---- outlet sales -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OutletSalesResponse outletSales(String username, Long companyId, int days, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.OUTLET_SALES);
        Period period = period(today, days);
        Period previous = period(today.minusDays(days), days);

        List<DatedAmount> sales = queries.outletSalesByDay(companyId, period.from(), period.to());
        List<DatedAmount> returns = queries.outletReturnsByDay(companyId, period.from(), period.to());
        Map<Instant, BigDecimal> salesByDay = byDay(sales);
        Map<Instant, BigDecimal> returnsByDay = byDay(returns);

        List<OutletSalesResponse.DailyPoint> daily = period.days().stream().map(day -> {
            BigDecimal s = salesByDay.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal r = returnsByDay.getOrDefault(day, BigDecimal.ZERO);
            return new OutletSalesResponse.DailyPoint(day, s, r, s.subtract(r));
        }).toList();

        BigDecimal grossSales = sum(sales, DatedAmount::amount);
        BigDecimal returnTotal = sum(returns, DatedAmount::amount);
        BigDecimal previousNet = sum(queries.outletSalesByDay(companyId, previous.from(), previous.to()), DatedAmount::amount)
                .subtract(sum(queries.outletReturnsByDay(companyId, previous.from(), previous.to()), DatedAmount::amount));

        List<OutletSalesResponse.Ranked> topOutlets = netById(
                queries.outletSalesByOutlet(companyId, period.from(), period.to()),
                queries.outletReturnsByOutlet(companyId, period.from(), period.to())).values().stream()
                .sorted(Comparator.comparing(OutletSalesResponse.Ranked::netSales).reversed()).limit(TOP_RANKED).toList();
        List<OutletSalesResponse.Ranked> topAgents = netById(
                queries.agentSalesByDay(companyId, period.from(), period.to()),
                queries.agentReturnsByDay(companyId, period.from(), period.to())).values().stream()
                .sorted(Comparator.comparing(OutletSalesResponse.Ranked::netSales).reversed()).limit(TOP_RANKED).toList();

        return new OutletSalesResponse(days, period.from(), startOfDay(today), grossSales, returnTotal, grossSales.subtract(returnTotal),
                sales.stream().mapToLong(DatedAmount::documents).sum(), previousNet, daily, topOutlets, topAgents);
    }

    /** Sales minus returns per id (rows may repeat an id across days). */
    private static Map<Long, OutletSalesResponse.Ranked> netById(List<DatedAmount> sales, List<DatedAmount> returns) {
        Map<Long, OutletSalesResponse.Ranked> net = new HashMap<>();
        sales.forEach(s -> net.merge(s.id(), new OutletSalesResponse.Ranked(s.id(), s.name(), nz(s.amount())), DashboardService::add));
        returns.forEach(r -> net.merge(r.id(), new OutletSalesResponse.Ranked(r.id(), r.name(), nz(r.amount()).negate()), DashboardService::add));
        return net;
    }

    private static OutletSalesResponse.Ranked add(OutletSalesResponse.Ranked a, OutletSalesResponse.Ranked b) {
        return new OutletSalesResponse.Ranked(a.id(), a.name(), a.netSales().add(b.netSales()));
    }

    // ---- agent leaderboard ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AgentLeaderboardResponse agentLeaderboard(String username, Long companyId, int days, LocalDate asOf) {
        LocalDate today = begin(username, companyId, asOf, DashboardWidget.AGENT_LEADERBOARD);
        Period period = period(today, days);
        Period previous = period(today.minusDays(days), days);

        List<DatedAmount> sales = queries.agentSalesByDay(companyId, period.from(), period.to());
        List<DatedAmount> returns = queries.agentReturnsByDay(companyId, period.from(), period.to());
        Map<Long, OutletSalesResponse.Ranked> net = netById(sales, returns);

        Collection<OutletSalesResponse.Ranked> previousTotals = netById(queries.agentSalesByDay(companyId, previous.from(), previous.to()),
                queries.agentReturnsByDay(companyId, previous.from(), previous.to())).values();
        List<Long> previousOrder = previousTotals.stream()
                .sorted(Comparator.comparing(OutletSalesResponse.Ranked::netSales).reversed())
                .map(OutletSalesResponse.Ranked::id)
                .toList();
        Map<Long, BigDecimal> previousNet = previousTotals.stream()
                .collect(Collectors.toMap(OutletSalesResponse.Ranked::id, OutletSalesResponse.Ranked::netSales));

        Map<Long, List<DatedAmount>> salesByAgent = sales.stream().collect(Collectors.groupingBy(DatedAmount::id));
        Map<Long, List<DatedAmount>> returnsByAgent = returns.stream().collect(Collectors.groupingBy(DatedAmount::id));

        List<OutletSalesResponse.Ranked> ranked = net.values().stream()
                .sorted(Comparator.comparing(OutletSalesResponse.Ranked::netSales).reversed())
                .limit(TOP_AGENTS)
                .toList();
        List<AgentLeaderboardResponse.Agent> agents = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            OutletSalesResponse.Ranked r = ranked.get(i);
            List<DatedAmount> s = salesByAgent.getOrDefault(r.id(), List.of());
            List<DatedAmount> ret = returnsByAgent.getOrDefault(r.id(), List.of());
            Map<Instant, BigDecimal> sDay = byDay(s);
            Map<Instant, BigDecimal> rDay = byDay(ret);
            List<BigDecimal> dailyNet = period.days().stream()
                    .map(d -> sDay.getOrDefault(d, BigDecimal.ZERO).subtract(rDay.getOrDefault(d, BigDecimal.ZERO)))
                    .toList();
            int prevIndex = previousOrder.indexOf(r.id());
            agents.add(new AgentLeaderboardResponse.Agent(i + 1, r.id(), r.name(), r.netSales(),
                    sum(s, DatedAmount::amount), sum(ret, DatedAmount::amount),
                    s.stream().mapToLong(DatedAmount::documents).sum(),
                    previousNet.getOrDefault(r.id(), BigDecimal.ZERO),
                    prevIndex >= 0 ? prevIndex + 1 : null, dailyNet));
        }

        return new AgentLeaderboardResponse(days, period.from(), startOfDay(today), salesByAgent.size(),
                net.values().stream().map(OutletSalesResponse.Ranked::netSales).reduce(BigDecimal.ZERO, BigDecimal::add), agents);
    }

    // ---- transaction activity ---------------------------------------------------------------------------------

    /** Days are calendar days in {@code timeZone} (the caller's IANA zone, default UTC). */
    @Transactional(readOnly = true)
    public TransactionActivityResponse transactionActivity(String username, Long companyId, int days, LocalDate asOf, String timeZone) {
        ZoneId zone;
        try {
            zone = timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + timeZone);
        }
        assertCompanyAccess(username, requireCompany(companyId));
        LocalDate today = asOf != null ? asOf : LocalDate.now(zone);
        log.debug("User '{}' loading {} widget (companyId={}, asOf={})", username, DashboardWidget.TRANSACTION_ACTIVITY, companyId, today);

        LocalDate first = today.minusDays(days - 1L);
        List<LocalDate> dates = first.datesUntil(today.plusDays(1)).toList();
        List<DashboardQueries.ActivityRow> rows = queries.transactionActivity(companyId,
                first.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant(), zone.getId());

        Map<String, List<DashboardQueries.ActivityRow>> byType = rows.stream()
                .collect(Collectors.groupingBy(DashboardQueries.ActivityRow::transactionType));
        List<TransactionActivityResponse.TypeRow> types = byType.entrySet().stream().map(e -> {
            Map<LocalDate, Long> created = e.getValue().stream().filter(r -> r.eventType().equals("CREATED"))
                    .collect(Collectors.toMap(DashboardQueries.ActivityRow::day, DashboardQueries.ActivityRow::count, Long::sum));
            long voided = e.getValue().stream().filter(r -> r.eventType().equals("VOIDED")).mapToLong(DashboardQueries.ActivityRow::count).sum();
            List<Long> counts = dates.stream().map(d -> created.getOrDefault(d, 0L)).toList();
            return new TransactionActivityResponse.TypeRow(e.getKey(), counts.stream().mapToLong(Long::longValue).sum(), voided, counts);
        }).sorted(Comparator.comparingLong(TransactionActivityResponse.TypeRow::created).reversed()).toList();

        long[] perDay = new long[dates.size()];
        types.forEach(t -> { for (int i = 0; i < perDay.length; i++) perDay[i] += t.counts().get(i); });
        int busiest = 0;
        for (int i = 1; i < perDay.length; i++) if (perDay[i] > perDay[busiest]) busiest = i;

        return new TransactionActivityResponse(days, first, today,
                types.stream().mapToLong(TransactionActivityResponse.TypeRow::created).sum(),
                types.stream().mapToLong(TransactionActivityResponse.TypeRow::voided).sum(),
                perDay.length > 0 && perDay[busiest] > 0 ? dates.get(busiest) : null,
                perDay.length > 0 ? perDay[busiest] : 0, dates, types);
    }

    // ---- shared helpers ---------------------------------------------------------------------------------------

    /** [from, to) over UTC-midnight business dates, plus each day's instant (oldest first). */
    private record Period(Instant from, Instant to, List<Instant> days) {
    }

    private static Period period(LocalDate lastDay, int days) {
        LocalDate first = lastDay.minusDays(days - 1L);
        return new Period(startOfDay(first), startOfDay(lastDay.plusDays(1)),
                first.datesUntil(lastDay.plusDays(1)).map(DashboardService::startOfDay).toList());
    }

    /** Access check first (always), then resolves the caller's "today". */
    private LocalDate begin(String username, Long companyId, LocalDate asOf, DashboardWidget widget) {
        assertCompanyAccess(username, requireCompany(companyId));
        LocalDate today = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
        log.debug("User '{}' loading {} widget (companyId={}, asOf={})", username, widget, companyId, today);
        return today;
    }

    private static Map<Instant, BigDecimal> byDay(List<DatedAmount> rows) {
        return rows.stream().collect(Collectors.toMap(DatedAmount::date, r -> nz(r.amount()), BigDecimal::add));
    }

    private static int ageDays(Instant businessDate, LocalDate today) {
        LocalDate date = LocalDate.ofInstant(businessDate, ZoneOffset.UTC);
        return (int) Math.max(0, ChronoUnit.DAYS.between(date, today));
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).map(DashboardService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Null when every value is null (a hidden/absent measure stays hidden in the "Others" slice). */
    private static <T> BigDecimal sumNullable(Collection<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).allMatch(Objects::isNull) ? null : sum(rows, value);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // Widgets never mix companies: the caller must say which one (the controller falls back to X-Company-Id).
    private static Long requireCompany(Long companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        return companyId;
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
