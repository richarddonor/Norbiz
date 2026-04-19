package com.chardizard.Norbiz.config;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Permission;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Permissions
        Permission manageSystemPermission = findOrCreate("MANAGE_SYSTEM", "Security - System Manage");
        Permission viewUserPermission     = findOrCreate("VIEW_USER",     "Security - User View");
        Permission createUserPermission   = findOrCreate("CREATE_USER",   "Security - User Create");
        Permission updateUserPermission   = findOrCreate("UPDATE_USER",   "Security - User Update");
        Permission deleteUserPermission   = findOrCreate("DELETE_USER",   "Security - User Delete");
        Permission viewRolePermission     = findOrCreate("VIEW_ROLE",     "Security - Role View");
        Permission createRolePermission   = findOrCreate("CREATE_ROLE",   "Security - Role Create");
        Permission updateRolePermission   = findOrCreate("UPDATE_ROLE",   "Security - Role Update");
        Permission deleteRolePermission   = findOrCreate("DELETE_ROLE",   "Security - Role Delete");
        Permission viewItemPermission     = findOrCreate("VIEW_ITEM",     "Maintenance - Item View");
        Permission createItemPermission   = findOrCreate("CREATE_ITEM",   "Maintenance - Item Create");
        Permission updateItemPermission   = findOrCreate("UPDATE_ITEM",   "Maintenance - Item Update");
        Permission deleteItemPermission   = findOrCreate("DELETE_ITEM",   "Maintenance - Item Delete");

        // Roles — permissions are always synced on startup
        Role adminRole = roleRepository.findByName("ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("ADMIN");
            return r;
        });
        adminRole.setDisplayName("Administrator");
        adminRole.setPermissions(Set.of());
        roleRepository.save(adminRole);

        // SYSTEM_ADMIN: business-level access
        Role systemAdminRole = roleRepository.findByName("SYSTEM_ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("SYSTEM_ADMIN");
            return r;
        });
        systemAdminRole.setDisplayName("System Administrator");
        systemAdminRole.setPermissions(Set.of(
                viewUserPermission, createUserPermission, viewRolePermission,
                viewItemPermission, createItemPermission, updateItemPermission));
        roleRepository.save(systemAdminRole);

        // SUPER_ADMIN: complete access including system management
        Role superAdminRole = roleRepository.findByName("SUPER_ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("SUPER_ADMIN");
            return r;
        });
        superAdminRole.setDisplayName("Super Administrator");
        superAdminRole.setPermissions(Set.of(manageSystemPermission,
                viewUserPermission, createUserPermission, updateUserPermission, deleteUserPermission,
                viewRolePermission, createRolePermission, updateRolePermission, deleteRolePermission,
                viewItemPermission, createItemPermission, updateItemPermission, deleteItemPermission));
        roleRepository.save(superAdminRole);

        // Default company — super admin is pre-assigned; other users are assigned to tenants later
        Company defaultCompany = companyRepository.findByName("Norbiz").orElseGet(() -> {
            Company c = new Company();
            c.setName("Norbiz");
            return companyRepository.save(c);
        });

        // Seed users (skip if already present)
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@norbiz.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRoles(Set.of(adminRole));
            userRepository.save(admin);
        }

        if (userRepository.findByUsername("super_admin").isEmpty()) {
            User superAdmin = new User();
            superAdmin.setUsername("super_admin");
            superAdmin.setEmail("super.admin@norbiz.com");
            superAdmin.setPassword(passwordEncoder.encode("password"));
            superAdmin.setRoles(Set.of(superAdminRole));
            superAdmin.setCompanies(Set.of(defaultCompany));
            userRepository.save(superAdmin);
        }

        if (userRepository.findByUsername("system_admin").isEmpty()) {
            User systemAdmin = new User();
            systemAdmin.setUsername("system_admin");
            systemAdmin.setEmail("system.admin@norbiz.com");
            systemAdmin.setPassword(passwordEncoder.encode("password"));
            systemAdmin.setRoles(Set.of(systemAdminRole));
            userRepository.save(systemAdmin);
        }
    }

    private Permission findOrCreate(String name, String description) {
        Permission p = permissionRepository.findByName(name).orElseGet(() -> {
            Permission newP = new Permission();
            newP.setName(name);
            return newP;
        });
        p.setDescription(description);
        return permissionRepository.save(p);
    }
}