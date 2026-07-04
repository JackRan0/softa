package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * Declares a database index on the annotated {@link Model} class.
 * <p>
 * Repeatable: stack multiple {@code @Index(...)} declarations on the same
 * class to declare multiple indexes.
 *
 * <pre>{@code
 * @Index(fields = {"status"})
 * @Index(indexName = "uk_customer_email", fields = {"email"}, unique = true)
 * @Index(fields = {"createdTime", "tenantId"})
 * @Model(...)
 * public class Customer { ... }
 * }</pre>
 *
 * <p>{@link #fields()} references {@code @Field}-annotated Java field names
 * (camelCase). The parser resolves these to DB column names via each field's
 * {@link Field#columnName()} (or {@code snake_case(fieldName)} default).
 *
 * <p>The {@code MetadataAnnotationScanner} writes resulting {@code sys_model_index}
 * rows; the DDL orchestrator emits
 * {@code CREATE INDEX} / {@code ADD INDEX} (auto-executed) or {@code DROP INDEX}
 * (warn-only, never auto-executed).
 *
 * <p>Note: this annotation does NOT auto-derive an index from
 * {@link Model#businessKey()}. Multi-tenant models typically want
 * {@code UNIQUE (tenant_id, businessKey...)} which has tenant-aware semantics
 * not yet expressed by this annotation; declare such indexes explicitly via
 * a separate {@code @Index(fields = {"tenantId", "code"}, unique = true)}.
 *
 * <p><b>Requires {@code metadata-starter}</b> on the classpath to take effect
 * (see {@link Model}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Indexes.class)
public @interface Index {

    /**
     * SQL index identifier. Empty = parser auto-derives as
     * {@code uk_<table>_<col1>_<col2>...} (unique) or
     * {@code idx_<table>_<col1>_<col2>...} (non-unique). An explicit name is
     * trusted as the developer's unique name; an auto-derived name embeds the
     * table, so it is unique by construction. Index names must be
     * <b>globally unique</b> across all models (PostgreSQL namespaces index
     * names per schema); a collision fails fast at boot. Capped at 60 chars
     * (safe under both MySQL 64 and PostgreSQL 63); an over-length name (explicit
     * or derived) is rejected — supply a shorter explicit name. Maps to
     * {@code SysModelIndex.indexName} / {@code sys_model_index.index_name}.
     */
    String indexName() default "";

    /**
     * Field names (in the model's {@code @Field} domain — camelCase Java
     * field names, NOT column names). Required, non-empty.
     */
    String[] fields();

    /**
     * Unique constraint. Default false (regular non-unique index).
     */
    boolean unique() default false;

    /**
     * End-user message shown when <b>this</b> unique constraint is violated.
     * Only valid when {@link #unique()} is true (a message on a non-unique
     * index is rejected at boot). A complete, self-contained English sentence
     * with no {@code {0}} arguments — it doubles as its own i18n key (resolved
     * via {@code I18n.get(message)}). Empty (the common case) = fall back to a
     * message composed from the member fields' labels. Maps to
     * {@code SysModelIndex.message} / {@code sys_model_index.message}.
     */
    String message() default "";
}
