package com.habitcoach.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /** There is at most one profile (no auth, single local device — see
     * UserProfile's javadoc); the oldest row is always THE profile, in the
     * unlikely event more than one ever exists. */
    Optional<UserProfile> findTopByOrderByIdAsc();
}
