package com.chardizard.Norbiz.security;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The company-membership check every service does before reading or writing a company-scoped record
 * (CLAUDE.md "Multi-tenancy model"), shared by the accounting services instead of a private copy each.
 * SUPER_ADMIN bypasses it.
 */
@Component
@RequiredArgsConstructor
public class CompanyAccess {

    private static final Logger log = LoggerFactory.getLogger(CompanyAccess.class);

    private final UserRepository userRepository;

    public void assertCompanyAccess(String username, Long companyId) {
        User user = loadUser(username);
        if (isSuperAdmin(user)) return;
        boolean hasAccess = user.getCompanies().stream().anyMatch(c -> c.getId().equals(companyId));
        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }

    /** Companies whose records a list may show: null = no restriction (SUPER_ADMIN), empty = none. */
    public List<Long> visibleCompanyIds(String username) {
        User user = loadUser(username);
        if (isSuperAdmin(user)) return null;
        return user.getCompanies().stream().map(Company::getId).toList();
    }

    private User loadUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private static boolean isSuperAdmin(User user) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
    }
}
