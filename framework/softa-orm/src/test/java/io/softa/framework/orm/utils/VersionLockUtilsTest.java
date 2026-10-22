package io.softa.framework.orm.utils;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionLockUtilsTest {

    @Test
    void versionsCompareAsNumbersWhateverTypeEachArrivedAs() {
        // An INT column reads back as Integer, a BIGINT one as Long; message-starter sends a long,
        // HCM entities an Integer, and a Long reaches the browser (and comes back) as a string.
        assertThat(VersionLockUtils.sameVersion(3L, 3)).isTrue();
        assertThat(VersionLockUtils.sameVersion(3, 3L)).isTrue();
        assertThat(VersionLockUtils.sameVersion("3", 3L)).isTrue();
        assertThat(VersionLockUtils.sameVersion(" 3 ", 3)).isTrue();
        assertThat(VersionLockUtils.sameVersion((short) 3, (byte) 3)).isTrue();
        assertThat(VersionLockUtils.sameVersion(BigInteger.valueOf(3), 3L)).isTrue();
        assertThat(VersionLockUtils.sameVersion(2, 3L)).isFalse();
        assertThat(VersionLockUtils.sameVersion("4", 3)).isFalse();
    }

    @Test
    void rejectsNullAndNonIntegerVersions() {
        assertThatThrownBy(() -> VersionLockUtils.sameVersion(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        assertThatThrownBy(() -> VersionLockUtils.sameVersion("v1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1");
        assertThatThrownBy(() -> VersionLockUtils.sameVersion(1.0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Double.class.getName());
    }
}
