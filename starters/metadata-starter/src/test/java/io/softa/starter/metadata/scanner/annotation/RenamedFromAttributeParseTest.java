package io.softa.starter.metadata.scanner.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * The single-String {@code renamedFrom} attribute on {@code @Model} / {@code @Field} is the
 * sole rename declaration (the standalone {@code @RenamedFrom} annotation has been retired). The parser
 * reads it into the {@link RenameDeclarations} {@code DiffEngine} consumes, and keeps the parse-time
 * guards. Single-step (no chain): multi-version lineage is handled by studio snapshots /
 * annotation-lane manual migration, not accumulated here.
 */
class RenamedFromAttributeParseTest {

    private final AnnotationParser parser = new AnnotationParser();

    @Model(renamedFrom = "LegacyAccount")
    static class Account extends AuditableModel {
        @Field
        private Long id;

        @Override
        public Serializable getId() {
            return id;
        }

        @Field(renamedFrom = "acctNo")
        private String accountNumber;

        @Field
        private String name;
    }

    @Test
    void capturesModelAndFieldRenamesFromAttribute() {
        RenameDeclarations renames =
                parser.parse(List.of(Account.class), List.of()).renames();

        assertEquals("LegacyAccount", renames.modelOldNames().get("Account"));
        assertEquals("acctNo", renames.fieldOldNames().get("Account.accountNumber"));
        // A field with no rename declared carries no entry.
        assertTrue(renames.fieldOldNames().get("Account.name") == null);
    }

    /** Two fields declaring the same prior name → parse-time guard (a prior name can be claimed once). */
    @Model
    static class TwoClaimSamePrior extends AuditableModel {
        @Field
        private Long id;

        @Override
        public Serializable getId() {
            return id;
        }

        @Field(renamedFrom = "old")
        private String a;

        @Field(renamedFrom = "old")                 // same prior name claimed twice
        private String b;
    }

    @Test
    void rejectsTwoFieldsClaimingTheSamePriorName() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(TwoClaimSamePrior.class), List.of()));
        assertTrue(ex.getMessage().contains("can be claimed once"), ex.getMessage());
    }

    /** A model whose {@code renamedFrom} names a still-live model → parse-time guard. */
    @Model(renamedFrom = "Account")                 // claims a prior name that is a live model
    static class RenamedToLiveModel extends AuditableModel {
        @Field
        private Long id;

        @Override
        public Serializable getId() {
            return id;
        }
    }

    @Test
    void rejectsModelPriorNameThatIsStillLive() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(RenamedToLiveModel.class, Account.class), List.of()));
        assertTrue(ex.getMessage().contains("still a live model"), ex.getMessage());
    }

    /** The guards fire when the rename is declared via the attribute. */
    @Model
    static class PriorNameStillLive extends AuditableModel {
        @Field
        private Long id;

        @Override
        public Serializable getId() {
            return id;
        }

        @Field(renamedFrom = "name")                // "name" is still a live field → contradiction
        private String title;

        @Field
        private String name;
    }

    @Test
    void guardsFireForAttributeDeclaredRename() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(PriorNameStillLive.class), List.of()));
        assertTrue(ex.getMessage().contains("still a live field"), ex.getMessage());
    }
}
