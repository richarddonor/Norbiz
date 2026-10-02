package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.TransactionActionRequest;
import com.chardizard.Norbiz.dto.TransactionAvailableActionResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.TransactionActionDefinitionRepository;
import com.chardizard.Norbiz.repositories.TransactionEventRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

// Transaction history + user-taken actions. See docs/TRANSACTION_ACTIONS.md.
@Service
@RequiredArgsConstructor
public class TransactionEventService {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventService.class);

    private final TransactionEventRepository transactionEventRepository;
    private final TransactionActionDefinitionRepository definitionRepository;
    private final TransactionLookupService transactionLookupService;
    private final UserRepository userRepository;

    // Called by the transaction services from inside their own create/void transaction, so the
    // history row commits or rolls back together with the transaction itself.
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSystemEvent(Company company, TransactionType type, Long transactionId, String referenceNumber,
                                  TransactionEventType eventType, String username, Instant performedAt) {
        if (eventType == TransactionEventType.ACTION) {
            throw new IllegalArgumentException("ACTION events must be recorded through takeAction");
        }
        TransactionEvent event = new TransactionEvent();
        event.setCompany(company);
        event.setTransactionType(type);
        event.setTransactionId(transactionId);
        event.setReferenceNumber(referenceNumber);
        event.setEventType(eventType);
        event.setPerformedBy(username);
        event.setPerformedAt(performedAt);
        transactionEventRepository.save(event);
    }

    @Transactional
    public TransactionEvent takeAction(TransactionType type, Long transactionId, TransactionActionRequest request, String username) {
        User user = loadUser(username);
        TransactionLookupService.TransactionHeader header = resolveForUser(type, transactionId, user);

        if (header.voided()) {
            throw new IllegalArgumentException("Cannot take actions on a voided transaction: " + header.referenceNumber());
        }

        TransactionActionDefinition definition = definitionRepository.findById(request.getActionDefinitionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction action definition not found: " + request.getActionDefinitionId()));
        if (!definition.getCompany().getId().equals(header.companyId()) || definition.getTransactionType() != type) {
            throw new IllegalArgumentException("Action '" + definition.getCode() + "' is not configured for this transaction");
        }
        if (!definition.isActive()) {
            throw new IllegalArgumentException("Action '" + definition.getCode() + "' is inactive");
        }

        if (!isAllowed(user, definition)) {
            log.warn("User '{}' denied action '{}' on {} '{}' (id={}): role not allowed",
                    username, definition.getCode(), type, header.referenceNumber(), transactionId);
            throw new SecurityException("You are not allowed to take action: " + definition.getName());
        }

        if (transactionEventRepository.existsByTransactionTypeAndTransactionIdAndActionDefinitionIdAndPerformedBy(
                type, transactionId, definition.getId(), username)) {
            throw new IllegalArgumentException(alreadyTakenMessage(definition));
        }

        Set<Long> takenIds = takenActions(type, transactionId).stream()
                .map(e -> e.getActionDefinition().getId())
                .collect(Collectors.toSet());
        List<String> missing = missingPrerequisites(definition, takenIds);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Action '" + definition.getName()
                    + "' requires these actions first: " + String.join(", ", missing));
        }

        TransactionEvent event = new TransactionEvent();
        event.setCompany(definition.getCompany());
        event.setTransactionType(type);
        event.setTransactionId(transactionId);
        event.setReferenceNumber(header.referenceNumber());
        event.setEventType(TransactionEventType.ACTION);
        event.setActionDefinition(definition);
        event.setActionCode(definition.getCode());
        event.setActionName(definition.getName());
        event.setPerformedBy(username);
        event.setPerformedAt(Instant.now());
        event.setRemarks(request.getRemarks());

        TransactionEvent saved;
        try {
            saved = transactionEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent double-submit by the same user — TXN_EVENT_ACTION_USER_UQ is the backstop.
            throw new IllegalArgumentException(alreadyTakenMessage(definition));
        }

        log.info("User '{}' took action '{}' on {} '{}' (id={})",
                username, definition.getCode(), type, header.referenceNumber(), transactionId);
        return saved;
    }

    public Page<TransactionEvent> findHistory(TransactionType type, Long transactionId, String username, Pageable pageable) {
        resolveForUser(type, transactionId, loadUser(username));
        return transactionEventRepository.findByTransactionTypeAndTransactionIdOrderByPerformedAtAscIdAsc(type, transactionId, pageable);
    }

    public List<TransactionAvailableActionResponse> findAvailableActions(TransactionType type, Long transactionId, String username) {
        User user = loadUser(username);
        TransactionLookupService.TransactionHeader header = resolveForUser(type, transactionId, user);

        List<TransactionEvent> taken = takenActions(type, transactionId);
        Set<Long> takenIds = taken.stream().map(e -> e.getActionDefinition().getId()).collect(Collectors.toSet());
        Map<Long, List<String>> takenByDefinition = taken.stream().collect(Collectors.groupingBy(
                e -> e.getActionDefinition().getId(), Collectors.mapping(TransactionEvent::getPerformedBy, Collectors.toList())));

        return definitionRepository.findByCompanyIdAndTransactionTypeAndActiveTrueOrderBySortOrderAscNameAsc(header.companyId(), type)
                .stream()
                .map(definition -> {
                    List<String> takenBy = takenByDefinition.getOrDefault(definition.getId(), List.of());
                    List<String> missing = missingPrerequisites(definition, takenIds);

                    TransactionAvailableActionResponse res = new TransactionAvailableActionResponse();
                    res.setActionDefinitionId(definition.getId());
                    res.setCode(definition.getCode());
                    res.setName(definition.getName());
                    res.setSortOrder(definition.getSortOrder());
                    res.setTakenBy(takenBy);
                    res.setTakenByMe(takenBy.contains(username));
                    res.setAllowedForMe(isAllowed(user, definition));
                    res.setMissingPrerequisites(missing);
                    res.setPrerequisitesMet(missing.isEmpty());
                    res.setCanTake(!header.voided() && res.isAllowedForMe() && res.isPrerequisitesMet() && !res.isTakenByMe());
                    return res;
                })
                .toList();
    }

    private List<TransactionEvent> takenActions(TransactionType type, Long transactionId) {
        return transactionEventRepository.findByTransactionTypeAndTransactionIdAndEventType(
                type, transactionId, TransactionEventType.ACTION);
    }

    private List<String> missingPrerequisites(TransactionActionDefinition definition, Set<Long> takenIds) {
        return definition.getPrerequisites().stream()
                .filter(p -> !takenIds.contains(p.getId()))
                .sorted(Comparator.comparingInt(TransactionActionDefinition::getSortOrder))
                .map(TransactionActionDefinition::getName)
                .toList();
    }

    private boolean isAllowed(User user, TransactionActionDefinition definition) {
        if (isSuperAdmin(user)) return true;
        Set<Long> allowedRoleIds = definition.getAllowedRoles().stream().map(Role::getId).collect(Collectors.toSet());
        return user.getRoles().stream().anyMatch(r -> allowedRoleIds.contains(r.getId()));
    }

    // Company check for the transaction itself, plus the transaction type's VIEW_ permission —
    // the generic /transactions/{type}/{id} endpoints can't express that in a static @PreAuthorize.
    private TransactionLookupService.TransactionHeader resolveForUser(TransactionType type, Long transactionId, User user) {
        TransactionLookupService.TransactionHeader header = transactionLookupService.resolve(type, transactionId);

        if (isSuperAdmin(user)) return header;

        boolean canView = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .anyMatch(p -> p.getName().equals(type.getViewPermission()));
        if (!canView) {
            log.warn("User '{}' denied {} history/actions: missing {}", user.getUsername(), type, type.getViewPermission());
            throw new SecurityException("Missing permission: " + type.getViewPermission());
        }

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(header.companyId()));
        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", user.getUsername(), header.companyId());
            throw new SecurityException("Access denied to company: " + header.companyId());
        }
        return header;
    }

    private User loadUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private boolean isSuperAdmin(User user) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
    }

    private String alreadyTakenMessage(TransactionActionDefinition definition) {
        return "You have already taken action '" + definition.getName() + "' on this transaction";
    }
}
