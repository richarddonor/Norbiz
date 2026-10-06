package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.models.UserPreference;
import com.chardizard.Norbiz.repositories.UserPreferenceRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** The caller's own UI preferences. Always resolved from the authenticated username, so a user
 * can only ever read or write their own. */
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceService.class);

    private final UserPreferenceRepository userPreferenceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Optional<UserPreference> find(String username, String key) {
        return userPreferenceRepository.findByUserIdAndPrefKey(userId(username), key);
    }

    @Transactional
    public UserPreference save(String username, String key, String value) {
        Long userId = userId(username);
        userPreferenceRepository.upsert(userId, key, value);
        log.info("User '{}' saved preference '{}'", username, key);
        return userPreferenceRepository.findByUserIdAndPrefKey(userId, key).orElseThrow();
    }

    @Transactional
    public void delete(String username, String key) {
        int deleted = userPreferenceRepository.deleteByUserIdAndPrefKey(userId(username), key);
        log.info("User '{}' cleared preference '{}' ({} row(s))", username, key, deleted);
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }
}
