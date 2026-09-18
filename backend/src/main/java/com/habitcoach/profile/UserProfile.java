package com.habitcoach.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * The app has no real authentication (by design — see CLAUDE_CONTEXT.md
 * §9); instead every profile is scoped to an anonymous per-installation
 * deviceId (see mobile's deviceId.ts, sent as the X-Device-Id header and
 * threaded through by ProfileController/ProfileService). At most one
 * UserProfile ever exists PER deviceId — this is what stops a fresh
 * install/new device from ever seeing another device's onboarding data. It
 * is never habit-scoped beyond that — one profile informs coaching for
 * every habit belonging to the same device.
 *
 * @ColumnDefault is required (not cosmetic): adding a NOT NULL column via
 * ddl-auto=update against an existing, non-empty user_profile table fails
 * without a DB-level default to backfill existing rows with. Any
 * pre-existing row (saved before device-scoping existed) is attributed to
 * this fixed sentinel device id rather than being deleted or silently
 * merged into a real device's data.
 */
@Entity
@Table(name = "user_profile")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    @ColumnDefault("'legacy-device'")
    private String deviceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer age;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserProfile() {
        // JPA
    }

    public UserProfile(String deviceId, String name, Integer age, Gender gender) {
        this.deviceId = deviceId;
        this.name = name;
        this.age = age;
        this.gender = gender;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
