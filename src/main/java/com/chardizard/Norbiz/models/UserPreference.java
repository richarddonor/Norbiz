package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

// A user's own UI setting (e.g. a list page's saved column layout), keyed by a frontend-chosen
// string. Belongs to the user, not to a company — the same layout applies in every company the
// user can switch to. Not Auditable: personal view state would only be noise in the audit log.
@Getter
@Setter
@Entity
@Table(
    name = "user_preferences",
    uniqueConstraints = @UniqueConstraint(name = "USER_PREFERENCES_USER_KEY_UQ", columnNames = {"user_id", "pref_key"})
)
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Preferences go with the user: deleting the user deletes them at the database level.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "USER_PREFERENCES_USER_ID_FK"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "pref_key", nullable = false, length = 100)
    private String prefKey;

    // Opaque JSON owned by the frontend — the backend never inspects it (same convention as
    // DocumentTemplate.layout, hence TEXT rather than the 255-char string rule).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
