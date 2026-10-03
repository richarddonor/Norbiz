package com.chardizard.Norbiz.cache;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cache scope for the {@code findAllForUser}-style list endpoints: mirrors their company
 * scoping exactly (SUPER_ADMIN sees every company, everyone else sees their memberships), so two
 * users share a cached page only if the service would have returned them the same rows.
 */
@Component
@RequiredArgsConstructor
public class CacheScopeResolver {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CacheScope forUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        boolean superAdmin = user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
        if (superAdmin) return CacheScope.global();
        return CacheScope.companies(user.getCompanies().stream().map(Company::getId).toList());
    }
}
