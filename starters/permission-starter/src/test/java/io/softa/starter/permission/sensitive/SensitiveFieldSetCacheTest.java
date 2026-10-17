package io.softa.starter.permission.sensitive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import io.softa.starter.permission.spi.SensitiveFieldSetSource.SensitiveFieldSetDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveFieldSetCacheTest {

    private SensitiveFieldSetCache cache;

    private static SensitiveFieldSetDef sfs(String id, String model, String name,
                                            List<String> codes, List<String> attached) {
        return new SensitiveFieldSetDef(
                id, model,
                new HashSet<>(codes),
                name,
                attached == null ? null : new HashSet<>(attached));
    }

    private void primeWith(List<SensitiveFieldSetDef> rows) {
        cache = new SensitiveFieldSetCache(() -> rows);
        cache.reload();
    }

    @Test
    void reload_loadFailure_throws() {
        SensitiveFieldSetCache failing = new SensitiveFieldSetCache(() -> {
            throw new RuntimeException("db down");
        });
        assertThatThrownBy(failing::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SensitiveFieldSetCache load failed");
    }

    @Test
    void reload_skipsRowsWithNullIdOrModel() {
        SensitiveFieldSetDef bad1 = new SensitiveFieldSetDef(null, "Employee", Set.of(), null, null);
        SensitiveFieldSetDef bad2 = new SensitiveFieldSetDef("noModel", null, Set.of(), null, null);
        primeWith(List.of(bad1, bad2, sfs("comp", "Employee", "Compensation", List.of("salary"), null)));

        assertThat(cache.hasSensitiveFieldsOn("Employee")).isTrue();
        assertThat(cache.allSensitiveFieldsOn("Employee")).containsExactly("salary");
    }

    @Test
    void modelOf_knownSetId_returnsModel() {
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.modelOf("comp")).isEqualTo("Employee");
    }

    @Test
    void modelOf_unknownSetId_returnsNull() {
        primeWith(List.of());
        assertThat(cache.modelOf("nope")).isNull();
    }

    @Test
    void hasSensitiveFieldsOn_unknownModel_false() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        assertThat(cache.hasSensitiveFieldsOn("Department")).isFalse();
    }

    @Test
    void allSensitiveFieldsOn_unionsAcrossSets() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary", "bonus"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        assertThat(cache.allSensitiveFieldsOn("Employee"))
                .containsExactlyInAnyOrder("salary", "bonus", "bankAccount");
    }

    @Test
    void grantedFieldsFor_ignoresGrantsBoundToDifferentModel() {
        // "comp" is on Employee. If asker requests grant for Department using
        // setId "comp", it must return empty — no cross-model leak.
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.grantedFieldsFor("Department", Set.of("comp"))).isEmpty();
    }

    @Test
    void grantedFieldsFor_returnsUnionOfMatchingSets() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        assertThat(cache.grantedFieldsFor("Employee", Set.of("comp", "bank")))
                .containsExactlyInAnyOrder("salary", "bankAccount");
    }

    @Test
    void computeForbiddenFields_grantedCoversAll_returnsEmpty() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of("comp"));
        assertThat(blocked).isEmpty();
    }

    @Test
    void computeForbiddenFields_partialGrant_leavesRestBlocked() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of("comp"));
        assertThat(blocked).containsExactly("bankAccount");
    }

    @Test
    void computeForbiddenFields_noGrants_allSensitiveBlocked() {
        primeWith(List.of(
                sfs("comp", "Employee", "Compensation", List.of("salary"), null),
                sfs("bank", "Employee", "Bank", List.of("bankAccount"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Employee", Set.of());
        assertThat(blocked).containsExactlyInAnyOrder("salary", "bankAccount");
    }

    @Test
    void computeForbiddenFields_noSensitiveOnModel_earlyEmpty() {
        primeWith(List.of(sfs("comp", "Employee", "Comp", List.of("salary"), null)));
        Set<String> blocked = cache.computeForbiddenFields("Department", Set.of("comp"));
        assertThat(blocked).isEmpty();
    }

    @Test
    void setIdsAttachedTo_findsAttachmentHint() {
        primeWith(List.of(
                sfs("bank", "EmpBankAccount", "Bank", List.of("acctNo"), List.of("Employee"))));
        assertThat(cache.setIdsAttachedTo("Employee")).containsExactly("bank");
    }

    @Test
    void setIdsOwnedBy_findsCanonicalOwnership() {
        primeWith(List.of(
                sfs("bank", "EmpBankAccount", "Bank", List.of("acctNo"), null),
                sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.setIdsOwnedBy("EmpBankAccount")).containsExactly("bank");
        assertThat(cache.setIdsOwnedBy("Employee")).containsExactly("comp");
    }

    @Test
    void nameOf_returnsDisplayName() {
        primeWith(List.of(sfs("comp", "Employee", "Compensation", List.of("salary"), null)));
        assertThat(cache.nameOf("comp")).isEqualTo("Compensation");
        assertThat(cache.nameOf("unknown")).isNull();
    }

    @Test
    void shortNameOf_prefersTheShortNameAndFallsBackToTheName() {
        primeWith(List.of(
                new SensitiveFieldSetDef("pay", "Employee", Set.of("salary"), "Pay Details", null, "Pay"),
                new SensitiveFieldSetDef("bank", "Employee", Set.of("iban"), "Banking", null, " "),
                sfs("plain", "Employee", "Contact", List.of("phone"), null)));

        assertThat(cache.shortNameOf("pay")).isEqualTo("Pay");
        assertThat(cache.shortNameOf("bank")).isEqualTo("Banking");
        assertThat(cache.shortNameOf("plain")).isEqualTo("Contact");
        assertThat(cache.shortNameOf("unknown")).isNull();
        assertThat(cache.shortNameOf(null)).isNull();
    }
}
