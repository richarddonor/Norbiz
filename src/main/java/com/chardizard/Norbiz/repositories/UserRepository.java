package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    List<User> findByRolesContaining(Role role);
}
