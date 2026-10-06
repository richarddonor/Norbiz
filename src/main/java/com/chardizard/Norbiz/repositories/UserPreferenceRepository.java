package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);

    // Atomic upsert, so two quick saves of the same key can't race into a unique-key violation.
    @Modifying
    @Query(value = """
            INSERT INTO user_preferences (user_id, pref_key, value, updated_at)
            VALUES (:userId, :prefKey, :value, NOW())
            ON CONFLICT (user_id, pref_key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("prefKey") String prefKey, @Param("value") String value);

    @Modifying
    @Query("DELETE FROM UserPreference p WHERE p.user.id = :userId AND p.prefKey = :prefKey")
    int deleteByUserIdAndPrefKey(@Param("userId") Long userId, @Param("prefKey") String prefKey);
}
