package com.habitcoach.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.habitcoach.web.InvalidRequestException;

/**
 * Exercises ProfileService against a real (in-memory, for tests) database —
 * create, retrieve, persistence, upsert, and validation, covering the
 * ONBOARDING/PROFILE test requirements.
 */
@DataJpaTest
@Import(ProfileService.class)
class ProfileServiceTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserProfileRepository repository;

    @Test
    void noProfileInitially() {
        assertThat(profileService.getProfile()).isEmpty();
    }

    @Test
    void createsAndRetrievesAProfile() {
        UserProfile saved = profileService.saveProfile("Asha", 29, Gender.FEMALE);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Asha");
        assertThat(saved.getAge()).isEqualTo(29);
        assertThat(saved.getGender()).isEqualTo(Gender.FEMALE);

        assertThat(profileService.getProfile()).isPresent();
        assertThat(profileService.getProfile().get().getName()).isEqualTo("Asha");
    }

    @Test
    void persistsAcrossANewRepositoryLookup() {
        profileService.saveProfile("Ravi", 68, Gender.MALE);

        // Reload straight from the repository, not through the same
        // ProfileService instance's in-memory state — proves it actually
        // hit the database, not just a field cache.
        assertThat(repository.findTopByOrderByIdAsc()).isPresent();
        assertThat(repository.findTopByOrderByIdAsc().get().getName()).isEqualTo("Ravi");
    }

    @Test
    void savingAgainUpdatesTheSameRowRatherThanCreatingASecondOne() {
        profileService.saveProfile("Asha", 29, Gender.FEMALE);
        profileService.saveProfile("Asha K.", 30, Gender.FEMALE);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(profileService.getProfile().get().getName()).isEqualTo("Asha K.");
        assertThat(profileService.getProfile().get().getAge()).isEqualTo(30);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("  ", 20, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void ageOutOfRangeIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("Kid", 0, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> profileService.saveProfile("Old", 121, Gender.OTHER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void missingGenderIsRejected() {
        assertThatThrownBy(() -> profileService.saveProfile("Name", 20, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void deleteProfileClearsItBackToNotFound() {
        profileService.saveProfile("Asha", 29, Gender.FEMALE);

        profileService.deleteProfile();

        assertThat(profileService.getProfile()).isEmpty();
        assertThat(repository.count()).isEqualTo(0);
    }

    @Test
    void deleteProfileWhenNoneExistsIsANoOp() {
        assertThatCode(() -> profileService.deleteProfile()).doesNotThrowAnyException();
        assertThat(profileService.getProfile()).isEmpty();
    }
}
