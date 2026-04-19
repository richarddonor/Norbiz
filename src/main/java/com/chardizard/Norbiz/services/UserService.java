package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    public User register(User user, Set<Long> companyIds) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (companyIds != null && !companyIds.isEmpty()) {
            List<Company> companies = companyRepository.findAllById(companyIds);
            user.setCompanies(new HashSet<>(companies));
        }
        return userRepository.save(user);
    }

    public User update(Long id, String username, String displayName, String email,
                       Set<Long> roleIds, Set<Long> companyIds) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(email);
        if (roleIds != null) {
            List<Role> roles = roleRepository.findAllById(roleIds);
            if (roles.size() != roleIds.size()) {
                throw new IllegalArgumentException("One or more role IDs are invalid");
            }
            user.setRoles(new HashSet<>(roles));
        }
        if (companyIds != null) {
            List<Company> companies = companyRepository.findAllById(companyIds);
            user.setCompanies(new HashSet<>(companies));
        }
        return userRepository.save(user);
    }

    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        userRepository.deleteById(id);
    }

    /**
     * Resolves which companies to assign to a user being created/updated.
     * SUPER_ADMIN can assign any companies.
     * All other callers are restricted to companies they themselves belong to.
     */
    public Set<Long> resolveCompanyIds(User caller, Set<Long> requestedIds) {
        boolean isSuperAdmin = caller.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Set<Long> callerCompanyIds = caller.getCompanies().stream()
                .map(Company::getId)
                .collect(Collectors.toSet());

        if (isSuperAdmin) {
            return requestedIds != null ? requestedIds : Set.of();
        }

        if (requestedIds != null) {
            Set<Long> unauthorized = requestedIds.stream()
                    .filter(id -> !callerCompanyIds.contains(id))
                    .collect(Collectors.toSet());
            if (!unauthorized.isEmpty()) {
                throw new SecurityException("Access denied to companies: " + unauthorized);
            }
            return requestedIds;
        }

        return callerCompanyIds;
    }
}
