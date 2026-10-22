package com.chardizard.Norbiz.cache;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.ItemTag;
import com.chardizard.Norbiz.models.PriceType;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Pure unit test: Redis is replaced by an in-memory map behind a mocked StringRedisTemplate.
class QueryCacheTest {

    private final Map<String, String> store = new HashMap<>();
    private StringRedisTemplate redis;
    private GenerationStore generations;
    private CacheProperties properties;
    private QueryCache cache;

    private final Pageable pageable = PageRequest.of(0, 50, Sort.by("name"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        generations = mock(GenerationStore.class);
        when(generations.current(any(), any())).thenReturn(List.of("0"));
        properties = new CacheProperties();
        cache = new QueryCache(redis, generations, properties, ObservationRegistry.NOOP);
    }

    @Test
    void secondCallIsServedFromCacheWithoutHittingTheLoader() {
        AtomicInteger loads = new AtomicInteger();
        LookupResponse acme = new LookupResponse(1L, 7L, "ACME", "Acme", true);

        for (int i = 0; i < 2; i++) {
            Page<LookupResponse> page = cache.page(CacheRegion.LOOKUP_SUPPLIER, CacheScope.company(7L), Map.of("q", "ac"),
                    pageable, LookupResponse.class, () -> {
                        loads.incrementAndGet();
                        return new PageImpl<>(List.of(acme), pageable, 1);
                    });
            assertThat(page.getContent()).singleElement()
                    .satisfies(r -> assertThat(r.getName()).isEqualTo("Acme"))
                    .satisfies(r -> assertThat(r.isActive()).isTrue());
            assertThat(page.getTotalElements()).isEqualTo(1);
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void bumpedGenerationMissesTheOldEntry() {
        AtomicInteger loads = new AtomicInteger();
        Runnable call = () -> cache.page(CacheRegion.LOOKUP_SUPPLIER, CacheScope.company(7L), Map.of(), pageable,
                LookupResponse.class, () -> {
                    loads.incrementAndGet();
                    return Page.empty(pageable);
                });

        call.run();
        when(generations.current(any(), any())).thenReturn(List.of("1"));
        call.run();
        assertThat(loads).hasValue(2);
    }

    @Test
    void keyDependsOnScopeParamsGenerationsAndPaging() {
        String base = QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.company(1L), List.of("0"),
                Map.of("canViewCostPrice", true), pageable);

        assertThat(QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.company(2L), List.of("0"),
                Map.of("canViewCostPrice", true), pageable)).isNotEqualTo(base);
        assertThat(QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.company(1L), List.of("0"),
                Map.of("canViewCostPrice", false), pageable)).isNotEqualTo(base);
        assertThat(QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.company(1L), List.of("1"),
                Map.of("canViewCostPrice", true), pageable)).isNotEqualTo(base);
        assertThat(QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.company(1L), List.of("0"),
                Map.of("canViewCostPrice", true), PageRequest.of(1, 50, Sort.by("name")))).isNotEqualTo(base);
        assertThat(QueryCache.key(CacheRegion.LOOKUP_ITEM, CacheScope.global(), List.of("0"),
                Map.of("canViewCostPrice", true), pageable)).isNotEqualTo(base);
    }

    @Test
    void keyIgnoresParamOrderAndNullValues() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("filters", new LinkedHashMap<>(Map.of("name", "x", "code", "y")));
        a.put("q", null);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("filters", new TreeMap<>(Map.of("code", "y", "name", "x")));

        assertThat(QueryCache.key(CacheRegion.LIST_SUPPLIER, CacheScope.companies(List.of(2L, 1L)), List.of(), a, pageable))
                .isEqualTo(QueryCache.key(CacheRegion.LIST_SUPPLIER, CacheScope.companies(List.of(1L, 2L)), List.of(), b, pageable));
    }

    @Test
    void redisFailureFallsBackToTheLoader() {
        when(generations.current(any(), any())).thenThrow(new RedisConnectionFailureException("down"));

        Page<LookupResponse> page = cache.page(CacheRegion.LOOKUP_SUPPLIER, CacheScope.company(7L), Map.of(), pageable,
                LookupResponse.class, () -> new PageImpl<>(List.of(new LookupResponse(1L, 7L, "A", "A", true)), pageable, 1));

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        properties.setEnabled(false);
        cache.page(CacheRegion.LOOKUP_SUPPLIER, CacheScope.company(7L), Map.of(), pageable, LookupResponse.class,
                () -> Page.empty(pageable));
        verifyNoInteractions(redis, generations);
    }

    // Every cached DTO must survive a Redis round trip — a DTO that Jackson can't rebuild would turn
    // every request into a WARN + database read.
    @Test
    void cachedDtosRoundTrip() {
        ItemLookupResponse item = new ItemLookupResponse(1L, 7L, "I-1", "Bolt", true, Set.of(ItemTag.INVENTORY), new BigDecimal("1.50"), new BigDecimal("2.75"));
        assertThat(roundTrip(CacheRegion.LOOKUP_ITEM, ItemLookupResponse.class, item))
                .usingRecursiveComparison().isEqualTo(item);

        TransactionLookupResponse po = new TransactionLookupResponse(9L, 7L, "PO-1", Instant.parse("2026-01-02T03:04:05Z"),
                3L, "Acme", null, null, 4L, "Main", null, null, null, false, false,
                List.of(new TransactionLookupResponse.Line(1L, 1, 1L, "I-1", "Bolt", BigDecimal.TEN, BigDecimal.ONE, null, null)));
        assertThat(roundTrip(CacheRegion.LOOKUP_PURCHASE_ORDER, TransactionLookupResponse.class, po))
                .usingRecursiveComparison().isEqualTo(po);

        TransactionLookupResponse dr = new TransactionLookupResponse(10L, 7L, "DR-1", Instant.parse("2026-01-02T03:04:05Z"),
                null, null, 5L, "Outlet A", 6L, "Outlet A", null, null, null, false, false,
                List.of(new TransactionLookupResponse.Line(2L, 1, 1L, "I-1", "Bolt", BigDecimal.TEN, BigDecimal.ZERO, null, new BigDecimal("25.00"))));
        assertThat(roundTrip(CacheRegion.LOOKUP_DELIVERY_RECEIPT, TransactionLookupResponse.class, dr))
                .usingRecursiveComparison().isEqualTo(dr);

        TransactionLookupResponse odr = new TransactionLookupResponse(11L, 7L, "ODR-1", Instant.parse("2026-01-02T03:04:05Z"),
                null, null, 5L, "Outlet A", 6L, "Outlet A", 8L, "Juan Cruz", null, false, false,
                List.of(new TransactionLookupResponse.Line(3L, 1, 1L, "I-1", "Bolt", BigDecimal.TEN, BigDecimal.ONE, null, new BigDecimal("25.00"))));
        assertThat(roundTrip(CacheRegion.LOOKUP_OUTLET_DELIVERY_RECEIPT, TransactionLookupResponse.class, odr))
                .usingRecursiveComparison().isEqualTo(odr);

        ItemResponse itemRow = new ItemResponse();
        itemRow.setId(1L);
        itemRow.setName("Bolt");
        itemRow.setActive(true);
        itemRow.setTags(Set.of(ItemTag.INVENTORY));
        itemRow.setSkus(List.of("SKU-1"));
        ItemResponse.PriceEntry price = new ItemResponse.PriceEntry();
        price.setPriceType(PriceType.COST_PRICE);
        price.setAmount(new BigDecimal("2.00"));
        itemRow.setPrices(List.of(price));
        assertThat(roundTrip(CacheRegion.LIST_ITEM, ItemResponse.class, itemRow))
                .usingRecursiveComparison().isEqualTo(itemRow);

        UserResponse user = new UserResponse();
        user.setId(1L);
        user.setUsername("u");
        user.setRoles(Set.of("ADMIN"));
        UserResponse.CompanyInfo info = new UserResponse.CompanyInfo();
        info.setId(7L);
        info.setName("Co");
        user.setCompanies(List.of(info));
        assertThat(roundTrip(CacheRegion.LIST_USER, UserResponse.class, user))
                .usingRecursiveComparison().isEqualTo(user);

        TransactionActionDefinitionResponse def = new TransactionActionDefinitionResponse();
        def.setId(1L);
        def.setActive(true);
        TransactionActionDefinitionResponse.Ref ref = new TransactionActionDefinitionResponse.Ref();
        ref.setId(2L);
        ref.setCode("APPROVER");
        def.setAllowedRoles(List.of(ref));
        def.setPrerequisites(List.of());
        def.setCreatedAt(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(roundTrip(CacheRegion.LIST_TRANSACTION_ACTION_DEFINITION, TransactionActionDefinitionResponse.class, def))
                .usingRecursiveComparison().isEqualTo(def);

        for (Object dto : List.of(new BrandResponse(), new ItemCategoryResponse(), new ItemSkuResponse(), new WarehouseResponse(),
                new SupplierResponse(), new CustomerResponse(), new EmployeeResponse(), new DocumentTemplateResponse(),
                new RoleResponse(), new PermissionResponse(), new LookupResponse(1L, null, "c", "n", true))) {
            assertThat(roundTrip(CacheRegion.LIST_BRAND, dto.getClass(), dto)).usingRecursiveComparison().isEqualTo(dto);
        }
    }

    @Test
    void snapshotIsServedFromCacheUntilItExpires() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            LookupResponse r = cache.snapshot("DASHBOARD_X", 7L, Map.of("days", 30), Duration.ofMinutes(2), LookupResponse.class, () -> {
                loads.incrementAndGet();
                return new LookupResponse(1L, 7L, "A", "Acme", true);
            });
            assertThat(r.getName()).isEqualTo("Acme");
        }
        assertThat(loads).hasValue(1);
        // No generation counters: writes never invalidate a snapshot, only its TTL does.
        verifyNoInteractions(generations);
    }

    @Test
    void snapshotKeyDependsOnNameCompanyAndParams() {
        String base = QueryCache.snapshotKey("DASHBOARD_X", 1L, Map.of("days", 30, "canViewCostPrice", true));

        assertThat(QueryCache.snapshotKey("DASHBOARD_Y", 1L, Map.of("days", 30, "canViewCostPrice", true))).isNotEqualTo(base);
        assertThat(QueryCache.snapshotKey("DASHBOARD_X", 2L, Map.of("days", 30, "canViewCostPrice", true))).isNotEqualTo(base);
        assertThat(QueryCache.snapshotKey("DASHBOARD_X", 1L, Map.of("days", 7, "canViewCostPrice", true))).isNotEqualTo(base);
        assertThat(QueryCache.snapshotKey("DASHBOARD_X", 1L, Map.of("days", 30, "canViewCostPrice", false))).isNotEqualTo(base);
        assertThat(QueryCache.snapshotKey("DASHBOARD_X", 1L, Map.of("canViewCostPrice", true, "days", 30))).isEqualTo(base);
    }

    @Test
    void snapshotRedisFailureFallsBackToTheLoader() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(cache.snapshot("DASHBOARD_X", 7L, Map.of(), Duration.ofMinutes(2), String.class, () -> "live")).isEqualTo("live");
    }

    // Every dashboard widget response is cached as a snapshot, so each must survive a Redis round trip.
    @Test
    void dashboardDtosRoundTrip() {
        for (Class<?> type : List.of(BacklogResponse.class, StockInTransitResponse.class, OutletStockHealthResponse.class,
                InventoryAdjustmentTrendResponse.class, OutletSalesResponse.class, AgentLeaderboardResponse.class,
                TransactionActivityResponse.class, SubledgerSummaryResponse.class, CashAndChecksResponse.class)) {
            assertSnapshotRoundTrip(type);
        }
    }

    private <T> void assertSnapshotRoundTrip(Class<T> type) {
        T dto = type.cast(sample(type, type));
        store.clear();
        cache.snapshot("T", 7L, Map.of(), Duration.ofMinutes(2), type, () -> dto);
        T hit = cache.snapshot("T", 7L, Map.of(), Duration.ofMinutes(2), type,
                () -> { throw new AssertionError("expected a cache hit for " + type.getSimpleName()); });
        assertThat(hit).as(type.getSimpleName()).isEqualTo(dto);
    }

    /** A record with every component filled in (one element per list), so nested records and value types are exercised. */
    private static Object sample(java.lang.reflect.Type type, Class<?> owner) {
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw)) return List.of(sample(args[0], owner));
            if (Set.class.isAssignableFrom(raw)) return Set.of(sample(args[0], owner));
            if (Map.class.isAssignableFrom(raw)) return Map.of(sample(args[0], owner), sample(args[1], owner));
            throw new IllegalArgumentException("Unsupported generic type " + type + " in " + owner.getSimpleName());
        }
        Class<?> c = (Class<?>) type;
        if (c == long.class || c == Long.class) return 3L;
        if (c == int.class || c == Integer.class) return 4;
        if (c == double.class || c == Double.class) return 1.5d;
        if (c == boolean.class || c == Boolean.class) return true;
        if (c == String.class) return "x";
        if (c == BigDecimal.class) return new BigDecimal("12.50");
        if (c == Instant.class) return Instant.parse("2026-01-02T00:00:00Z");
        if (c == java.time.LocalDate.class) return java.time.LocalDate.parse("2026-01-02");
        if (c.isEnum()) return c.getEnumConstants()[0];
        if (c.isRecord()) {
            var components = c.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = sample(components[i].getGenericType(), c);
                types[i] = components[i].getType();
            }
            try {
                return c.getDeclaredConstructor(types).newInstance(values);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("Unsupported type " + c.getName() + " in " + owner.getSimpleName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T roundTrip(CacheRegion region, Class<T> type, Object dto) {
        store.clear();
        Map<String, Object> params = Map.of("type", type.getName());
        cache.page(region, CacheScope.company(7L), params, pageable, type, () -> new PageImpl(List.of(dto), pageable, 1));
        assertThat(store).hasSize(1);
        Page<T> hit = cache.page(region, CacheScope.company(7L), params, pageable, type,
                () -> { throw new AssertionError("expected a cache hit for " + type.getSimpleName()); });
        return hit.getContent().getFirst();
    }
}
