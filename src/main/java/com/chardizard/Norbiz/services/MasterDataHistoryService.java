package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse.FieldChange;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse.Kind;
import com.chardizard.Norbiz.models.AuditLog;
import com.chardizard.Norbiz.models.MasterDataType;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.AuditLogRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.Type;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

// Per-record change history for master-data forms, read from audit_logs. Unlike /audit-logs
// (SUPER_ADMIN only), this is open to anyone who can view the record itself: the type's VIEW_
// permission plus company access, checked through each entity service's scoped findById.
//
// Each entry is one change set (one database transaction): the record's own field changes plus
// those of its @AuditParent parts. Item updates rewrite every SKU/price row (delete + insert), so
// parts are matched by their natural key (SKU code, price type) and only the net difference is shown.
@Service
@RequiredArgsConstructor
public class MasterDataHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataHistoryService.class);

    // Stamped on every save by Auditable — shown on the form already, and pure noise in a diff.
    private static final Set<String> HIDDEN_FIELDS = Set.of("id", "createdAt", "createdBy", "updatedAt", "updatedBy");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A record type audited as part of another (its @AuditParent), keyed by a natural key. */
    private record PartSpec(String collection, String keyField, Function<String, String> caption, Set<String> moneyFields) {}

    // Order here is display order within an entry.
    private static final Map<String, PartSpec> PARTS = new LinkedHashMap<>();
    static {
        PARTS.put("ItemSku", new PartSpec("skus", "skuCode", code -> "SKU " + code, Set.of("unitPrice")));
        PARTS.put("ItemPrice", new PartSpec("prices", "priceType", MasterDataHistoryService::humanizeEnum, Set.of("amount")));
        // Bill of materials components are rewritten on every save (clear + re-add), so match them by line position.
        PARTS.put("BillOfMaterialLine", new PartSpec("components", "lineNumber", n -> "Component " + n, Set.of()));
    }

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final BrandService brandService;
    private final ItemCategoryService itemCategoryService;
    private final ItemGroupService itemGroupService;
    private final ItemService itemService;
    private final ItemSkuService itemSkuService;
    private final EmployeeService employeeService;
    private final WarehouseService warehouseService;
    private final SupplierService supplierService;
    private final CustomerService customerService;
    private final PullOutReasonService pullOutReasonService;
    private final BillOfMaterialService billOfMaterialService;

    @Transactional(readOnly = true)
    public Page<ChangeHistoryEntryResponse> findHistory(MasterDataType type, Long id, String username, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        assertCanView(user, type);
        assertRecordAccess(type, id, username);
        boolean canViewCostPrice = hasPermission(user, "VIEW_COST_PRICE");

        // The query orders by change time itself — a client-supplied sort would conflict with the grouping.
        Page<Object[]> keys = auditLogRepository.findChangeSetKeys(type.getEntityType(), id,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        List<String> changeSets = new ArrayList<>();
        List<Long> legacyIds = new ArrayList<>();
        for (Object[] row : keys.getContent()) {
            String key = (String) row[0];
            if (key.startsWith("#")) legacyIds.add(Long.valueOf(key.substring(1)));
            else changeSets.add(key);
        }
        // Never bind an empty IN list.
        if (changeSets.isEmpty()) changeSets.add("");
        if (legacyIds.isEmpty()) legacyIds.add(-1L);

        Map<String, List<AuditLog>> byKey = auditLogRepository
                .findInChangeSets(type.getEntityType(), id, changeSets, legacyIds).stream()
                .collect(Collectors.groupingBy(a -> a.getChangeSet() != null ? a.getChangeSet() : "#" + a.getId(),
                        LinkedHashMap::new, Collectors.toList()));

        log.debug("User '{}' viewed change history of {} (id={})", username, type, id);
        Labels labels = new Labels();
        // A save that changed nothing visible (e.g. an item re-saved as-is, which still rewrites its
        // SKU/price rows) is dropped; legacy entries stay, since their empty list means "not recorded".
        // The page can come back short — clients page on `last`, which still reflects the change sets.
        List<ChangeHistoryEntryResponse> entries = keys.getContent().stream()
                .map(row -> toEntry(type.getEntityType(), id, byKey.getOrDefault((String) row[0], List.of()), labels, canViewCostPrice))
                .filter(e -> !e.isDetailed() || e.getAction() != AuditAction.UPDATE || !e.getChanges().isEmpty())
                .toList();
        return new PageImpl<>(entries, keys.getPageable(), keys.getTotalElements());
    }

    private void assertCanView(User user, MasterDataType type) {
        if (!hasPermission(user, type.getViewPermission())) {
            log.warn("User '{}' denied {} history: missing {}", user.getUsername(), type, type.getViewPermission());
            throw new SecurityException("Missing permission: " + type.getViewPermission());
        }
    }

    private static boolean hasPermission(User user, String permission) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"))
                || user.getRoles().stream()
                        .flatMap(r -> r.getPermissions().stream())
                        .anyMatch(p -> p.getName().equals(permission));
    }

    // Company-scoped types go through the entity service's findById(id, username), which throws
    // if the record doesn't exist or belongs to a company the user can't access. A User isn't
    // owned by a single company (VIEW_USER lists every user), so existence is all there is to check.
    private void assertRecordAccess(MasterDataType type, Long id, String username) {
        switch (type) {
            case BRAND -> brandService.findById(id, username);
            case ITEM_CATEGORY -> itemCategoryService.findById(id, username);
            case ITEM_GROUP -> itemGroupService.findById(id, username);
            case ITEM -> itemService.findById(id, username);
            case ITEM_SKU -> itemSkuService.findById(id, username);
            case EMPLOYEE -> employeeService.findById(id, username);
            case WAREHOUSE -> warehouseService.findById(id, username);
            case SUPPLIER -> supplierService.findById(id, username);
            case CUSTOMER -> customerService.findById(id, username);
            case PULL_OUT_REASON -> pullOutReasonService.findById(id, username);
            case BILL_OF_MATERIAL -> billOfMaterialService.findById(id, username);
            case USER -> {
                if (!userRepository.existsById(id)) throw new IllegalArgumentException("User not found: " + id);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Building one entry
    // -----------------------------------------------------------------------

    private ChangeHistoryEntryResponse toEntry(String entityType, Long id, List<AuditLog> logs, Labels labels, boolean canViewCostPrice) {
        List<AuditLog> own = logs.stream()
                .filter(a -> entityType.equals(a.getEntityType()) && id.equals(a.getEntityId()))
                .toList();
        AuditLog latest = logs.getLast();

        AuditAction action = own.stream().anyMatch(a -> a.getAction() == AuditAction.CREATE) ? AuditAction.CREATE
                : own.stream().anyMatch(a -> a.getAction() == AuditAction.DELETE) ? AuditAction.DELETE
                : AuditAction.UPDATE;

        List<FieldChange> changes = new ArrayList<>(ownChanges(entityType, own, labels));
        PARTS.forEach((partType, spec) -> {
            List<AuditLog> partLogs = logs.stream().filter(a -> partType.equals(a.getEntityType())).toList();
            if (!partLogs.isEmpty()) changes.addAll(partChanges(partType, spec, partLogs, labels, canViewCostPrice));
        });

        ChangeHistoryEntryResponse res = new ChangeHistoryEntryResponse();
        res.setId(latest.getId());
        res.setAction(action);
        res.setChangedBy(latest.getChangedBy());
        res.setChangedAt(latest.getChangedAt());
        res.setChanges(changes);
        res.setDetailed(latest.getChangeSet() != null);
        return res;
    }

    /** The record's own logs in this change set, folded into one list (first old value, last new value). */
    private List<FieldChange> ownChanges(String entityType, List<AuditLog> own, Labels labels) {
        Map<String, Object[]> merged = new LinkedHashMap<>();   // field → {old, new}
        boolean created = false;
        for (AuditLog entry : own) {
            JsonNode root = parse(entry);
            if (root == null) continue;
            if (root.isArray()) {
                for (JsonNode c : root) {
                    String field = c.path("field").asText(null);
                    if (field == null) continue;
                    Object[] pair = merged.computeIfAbsent(field, f -> new Object[]{raw(c.get("oldValue")), null});
                    pair[1] = raw(c.get("newValue"));
                }
            } else if (root.isObject()) {
                // CREATE/DELETE snapshot, or the listener's UPDATE fallback when it had no prior state.
                created |= entry.getAction() == AuditAction.CREATE;
                boolean deleted = entry.getAction() == AuditAction.DELETE;
                root.properties().forEach(e -> {
                    Object[] pair = merged.computeIfAbsent(e.getKey(), f -> new Object[]{null, null});
                    if (deleted) pair[0] = raw(e.getValue()); else pair[1] = raw(e.getValue());
                });
            }
        }

        List<FieldChange> result = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : merged.entrySet()) {
            String field = e.getKey();
            if (HIDDEN_FIELDS.contains(field)) continue;
            String oldValue = labels.display(entityType, field, e.getValue()[0]);
            String newValue = labels.display(entityType, field, e.getValue()[1]);
            if (created) {
                result.add(new FieldChange(field, null, Kind.ADDED, null, newValue));
            } else if (!Objects.equals(oldValue, newValue)) {
                result.add(new FieldChange(field, null, Kind.CHANGED, oldValue, newValue));
            }
        }
        return result;
    }

    /** Net change of one kind of part (e.g. the item's SKUs) across this change set, matched by natural key. */
    private List<FieldChange> partChanges(String partType, PartSpec spec, List<AuditLog> logs, Labels labels, boolean canViewCostPrice) {
        Map<String, Map<String, Object>> before = new TreeMap<>();
        Map<String, Map<String, Object>> after = new TreeMap<>();
        List<FieldChange> result = new ArrayList<>();

        for (AuditLog entry : logs) {
            JsonNode root = parse(entry);
            if (root == null) continue;
            if (entry.getAction() == AuditAction.UPDATE && root.isArray()) {
                String key = labels.currentValue(partType, entry.getEntityId(), spec.keyField());
                for (JsonNode c : root) {
                    String field = c.path("field").asText(null);
                    if (field == null || HIDDEN_FIELDS.contains(field)) continue;
                    String oldValue = text(c.get("oldValue"));
                    String newValue = text(c.get("newValue"));
                    if (field.equals(spec.keyField())) key = newValue;
                    if (!visible(spec, key, canViewCostPrice)) continue;
                    result.add(new FieldChange(spec.collection() + "." + field, partCaption(spec, key, field), Kind.CHANGED,
                            formatPart(spec, field, oldValue), formatPart(spec, field, newValue)));
                }
                continue;
            }
            if (!root.isObject()) continue;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            root.properties().forEach(e -> snapshot.put(e.getKey(), raw(e.getValue())));
            String key = String.valueOf(snapshot.get(spec.keyField()));
            if (entry.getAction() == AuditAction.DELETE) {
                if (after.remove(key) == null) before.put(key, snapshot);
            } else {
                after.put(key, snapshot);
            }
        }

        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (!visible(spec, key, canViewCostPrice)) continue;
            Map<String, Object> was = before.get(key);
            Map<String, Object> now = after.get(key);
            if (was != null && now != null) {
                for (String field : now.keySet()) {
                    if (HIDDEN_FIELDS.contains(field) || field.equals(spec.keyField())) continue;
                    String oldValue = formatPart(spec, field, text(was.get(field)));
                    String newValue = formatPart(spec, field, text(now.get(field)));
                    if (!Objects.equals(oldValue, newValue)) {
                        result.add(new FieldChange(spec.collection() + "." + field, partCaption(spec, key, field), Kind.CHANGED, oldValue, newValue));
                    }
                }
            } else if (now != null) {
                result.add(new FieldChange(spec.collection(), spec.caption().apply(key), Kind.ADDED, null, partSummary(spec, now)));
            } else {
                result.add(new FieldChange(spec.collection(), spec.caption().apply(key), Kind.REMOVED, partSummary(spec, was), null));
            }
        }
        return result;
    }

    // Cost price is only ever shown to holders of VIEW_COST_PRICE; a price whose type can't be
    // determined any more is treated as possibly-cost.
    private static boolean visible(PartSpec spec, String key, boolean canViewCostPrice) {
        if (canViewCostPrice || !"priceType".equals(spec.keyField())) return true;
        return key != null && !key.startsWith("#") && !"COST_PRICE".equals(key);
    }

    /** "SKU ABC-1 · Unit Price"; a price's caption already names its value ("Cost Price"). */
    private static String partCaption(PartSpec spec, String key, String field) {
        String caption = spec.caption().apply(key);
        return spec.moneyFields().contains(field) && "priceType".equals(spec.keyField()) ? caption : caption + " · " + humanizeField(field);
    }

    /** A whole part in one value: a price's amount, or a SKU's other fields ("Unit Price 10.00"). */
    private static String partSummary(PartSpec spec, Map<String, Object> snapshot) {
        List<String> parts = new ArrayList<>();
        snapshot.forEach((field, value) -> {
            if (HIDDEN_FIELDS.contains(field) || field.equals(spec.keyField()) || field.equals("item")) return;
            String formatted = formatPart(spec, field, text(value));
            if ("priceType".equals(spec.keyField())) parts.add(formatted);
            else parts.add(humanizeField(field) + " " + formatted);
        });
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String formatPart(PartSpec spec, String field, String value) {
        if (value == null || !spec.moneyFields().contains(field)) return value;
        try {
            return new DecimalFormat("#,##0.00").format(new BigDecimal(value).setScale(2, RoundingMode.HALF_UP));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    // -----------------------------------------------------------------------
    // JSON / text helpers
    // -----------------------------------------------------------------------

    private static JsonNode parse(AuditLog entry) {
        if (entry.getChanges() == null || entry.getChanges().isBlank()) return null;
        try {
            return MAPPER.readTree(entry.getChanges());
        } catch (Exception e) {
            log.warn("Unparseable audit log changes (auditLogId={}): {}", entry.getId(), e.getMessage());
            return null;
        }
    }

    /** A logged value: null, a string, or (for collections) a list of strings. */
    private static Object raw(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(n -> values.add(n.asText()));
            return values;
        }
        return node.asText();
    }

    private static String text(Object value) {
        if (value instanceof JsonNode node) value = raw(node);
        if (value == null) return null;
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return value.toString();
    }

    /** "itemCategory" → "Item Category". */
    private static String humanizeField(String field) {
        String words = field.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** "COST_PRICE" → "Cost Price". */
    private static String humanizeEnum(String value) {
        if (value == null) return "";
        return Arrays.stream(value.toLowerCase().split("_"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    // -----------------------------------------------------------------------
    // Id → name resolution
    // -----------------------------------------------------------------------

    /**
     * Turns logged association ids into the related record's current name, via the JPA metamodel
     * (the logs store ids so a lazy association never has to be loaded mid-flush). Cached per request.
     */
    private class Labels {
        private final Map<String, String> cache = new HashMap<>();

        String display(String entityType, String field, Object raw) {
            if (raw == null) return null;
            Attribute<?, ?> attribute = attribute(entityType, field);
            if (raw instanceof List<?> list) {
                Class<?> target = null;
                if (attribute instanceof PluralAttribute<?, ?, ?> plural
                        && plural.getElementType().getPersistenceType() == Type.PersistenceType.ENTITY) {
                    target = plural.getElementType().getJavaType();
                }
                Class<?> entityClass = target;
                List<String> names = list.stream()
                        .map(String::valueOf)
                        .map(v -> entityClass != null ? name(entityClass, v) : v)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                return names.isEmpty() ? null : String.join(", ", names);
            }
            if (attribute != null && attribute.isAssociation() && !attribute.isCollection()) {
                return name(attribute.getJavaType(), raw.toString());
            }
            return raw.toString();
        }

        /** A part's current value of {@code field} (e.g. its SKU code) — from its creation log if it no
         *  longer exists — or "#id" if neither has it. */
        String currentValue(String entityType, Long id, String field) {
            EntityType<?> type = entityType(entityType);
            if (type == null || id == null) return "#" + id;
            Object entity = entityManager.find(type.getJavaType(), id);
            Object value = entity != null ? getter(entity, field) : null;
            if (value != null) return value instanceof Enum<?> e ? e.name() : value.toString();
            return auditLogRepository.findFirstByEntityTypeAndEntityIdAndActionOrderByIdDesc(entityType, id, AuditAction.CREATE)
                    .map(MasterDataHistoryService::parse)
                    .map(root -> root.path(field).asText(null))
                    .orElse("#" + id);
        }

        private String name(Class<?> entityClass, String id) {
            return cache.computeIfAbsent(entityClass.getSimpleName() + ":" + id, k -> {
                Object entity;
                try {
                    entity = entityManager.find(entityClass, Long.valueOf(id));
                } catch (Exception e) {
                    return id;
                }
                if (entity == null) return "#" + id + " (deleted)";
                for (String property : List.of("displayName", "name", "skuCode", "code", "username")) {
                    Object value = getter(entity, property);
                    if (value != null && !value.toString().isBlank()) return value.toString();
                }
                return "#" + id;
            });
        }

        private Attribute<?, ?> attribute(String entityType, String field) {
            EntityType<?> type = entityType(entityType);
            if (type == null) return null;
            try {
                return type.getAttribute(field);
            } catch (IllegalArgumentException e) {
                return null;   // field since renamed/removed
            }
        }

        private EntityType<?> entityType(String name) {
            return entityManager.getMetamodel().getEntities().stream()
                    .filter(e -> e.getName().equals(name))
                    .findFirst().orElse(null);
        }

        private Object getter(Object entity, String property) {
            try {
                Method method = entity.getClass().getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
                return method.invoke(entity);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
