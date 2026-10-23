package io.softa.starter.tenant.entity;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Model;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TenantOptionItem} is what every tenant-authored dropdown in the product is backed by, and
 * a dropdown has a search box. Typing in one sends {@code POST /TenantOptionItem/searchName} with no
 * {@code matchField} — the reference picker is shared by every relational field and has no business
 * knowing which column any given model is searched by — so the request names the virtual
 * {@code searchName} field and {@code WhereBuilder.parseSearchName} resolves it per model against
 * what {@code ModelManager.validateSearchName} settled at boot: a declared {@code searchName}, else
 * a field literally called {@code name}, else {@code id}.
 *
 * <p>This model has no field called {@code name} — its text column is {@code label} — so without a
 * declaration it lands on {@code id}. That branch exists to mean "no text search" and is the one
 * branch that does not assert the column is a STRING, so nothing downstream notices before the
 * keyword is matched against a {@code bigint}: {@code t.id ILIKE '%Dep%'}, which PostgreSQL rejects
 * while parsing ({@code operator does not exist: bigint ~~* unknown}, SQLSTATE 42883) and the user
 * receives as "System exception, please feedback to the administrator." with a Reference ID.
 *
 * <p>Two things make that omission easy to reintroduce, which is why it is pinned here rather than
 * left to review. It is invisible at compile time and at boot — the model serves every other
 * endpoint correctly, and the dropdown even opens and selects normally, because a blank keyword
 * builds no filter at all; only typing breaks it. And it is invisible on MySQL and H2, which coerce
 * the bigint and quietly return no rows, so the same declaration going missing degrades to "search
 * finds nothing" there and only announces itself on PostgreSQL.
 *
 * <p>Reads the annotation rather than the resolved metadata so it needs no context: the declaration
 * is the thing being protected, and {@code ModelManager}'s resolution of it is covered where that
 * resolution lives.
 */
class TenantOptionItemSearchNameTest {

    @Test
    void theModelDeclaresTheTextColumnsItIsSearchedBy() {
        Model model = TenantOptionItem.class.getAnnotation(Model.class);

        assertThat(model.searchName())
                .as("without this the virtual `searchName` resolves to `id` and a typed keyword 500s")
                .containsExactly("itemCode", "label");
    }

    /**
     * {@code ModelManager.validateSearchName} asserts STRING on every declared column and fails the
     * boot otherwise, so retyping one of these would take the application down rather than break the
     * dropdown quietly. Checked here so that arrives as a test failure instead.
     */
    @Test
    void everyDeclaredSearchColumnIsATextField() {
        for (String fieldName : TenantOptionItem.class.getAnnotation(Model.class).searchName()) {
            assertThat(declaredType(fieldName))
                    .as("searchName column `%s` must be a String", fieldName)
                    .isEqualTo(String.class);
        }
    }

    private static Class<?> declaredType(String fieldName) {
        try {
            Field field = TenantOptionItem.class.getDeclaredField(fieldName);
            return field.getType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "searchName names `" + fieldName + "`, which TenantOptionItem does not declare", e);
        }
    }
}
