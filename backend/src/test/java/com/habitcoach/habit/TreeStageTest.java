package com.habitcoach.habit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TreeStageTest {

    @Test
    void bandsCoverTheFullZeroToOneHundredRangeInOrder() {
        assertThat(TreeStage.of(0)).isEqualTo(TreeStage.DRY);
        assertThat(TreeStage.of(19)).isEqualTo(TreeStage.DRY);
        assertThat(TreeStage.of(20)).isEqualTo(TreeStage.RECOVERING);
        assertThat(TreeStage.of(39)).isEqualTo(TreeStage.RECOVERING);
        assertThat(TreeStage.of(40)).isEqualTo(TreeStage.GROWING);
        assertThat(TreeStage.of(59)).isEqualTo(TreeStage.GROWING);
        assertThat(TreeStage.of(60)).isEqualTo(TreeStage.HEALTHY);
        assertThat(TreeStage.of(79)).isEqualTo(TreeStage.HEALTHY);
        assertThat(TreeStage.of(80)).isEqualTo(TreeStage.THRIVING);
        assertThat(TreeStage.of(100)).isEqualTo(TreeStage.THRIVING);
    }

    @Test
    void serializesLowercaseOnTheWire() {
        assertThat(TreeStage.DRY.toJson()).isEqualTo("dry");
        assertThat(TreeStage.RECOVERING.toJson()).isEqualTo("recovering");
        assertThat(TreeStage.GROWING.toJson()).isEqualTo("growing");
        assertThat(TreeStage.HEALTHY.toJson()).isEqualTo("healthy");
        assertThat(TreeStage.THRIVING.toJson()).isEqualTo("thriving");
    }
}
