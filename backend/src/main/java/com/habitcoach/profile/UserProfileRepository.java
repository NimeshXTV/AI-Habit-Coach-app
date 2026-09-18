package com.habitcoach.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /** There is at most one profile PER deviceId (see UserProfile's
     * javadoc); the oldest row for that device is always THE profile, in
     * the unlikely event more than one ever exists for it. */
    Optional<UserProfile> findTopByDeviceIdOrderByIdAsc(String deviceId);

    void deleteByDeviceId(String deviceId);
}
