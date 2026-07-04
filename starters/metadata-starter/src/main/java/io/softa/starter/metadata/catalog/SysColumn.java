package io.softa.starter.metadata.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One annotation-managed {@code sys_*} column: its DB name, a typed accessor
 * pair into the entity, and the {@link Codec} that converts between the typed
 * value and the JDBC representation.
 *
 * <p>Built reflectively from the entity's own {@code @Field} (see
 * {@link SysCatalog}); drives equality, write and load uniformly.
 */
public final class SysColumn<E> {

    private final String name;
    private final String column;
    private final Function<E, Object> getter;
    private final BiConsumer<E, Object> setter;
    private final Codec codec;

    public SysColumn(String name, String column, Function<E, Object> getter, BiConsumer<E, Object> setter, Codec codec) {
        this.name = name;
        this.column = column;
        this.getter = getter;
        this.setter = setter;
        this.codec = codec;
    }

    /** Entity attribute (camelCase) name — the single source for any field-name list (e.g. the checksum). */
    public String name() {
        return name;
    }

    public String column() {
        return column;
    }

    /** JDBC bind value for this column from {@code e}. */
    public Object toDb(E e) {
        return codec.toDb(getter.apply(e));
    }

    /** Read this column from {@code rs} and set it on {@code e}. */
    public void read(E e, ResultSet rs) throws SQLException {
        setter.accept(e, codec.fromDb(rs, column));
    }

    /** Whether this column's typed value is equal between {@code a} and {@code b}. */
    public boolean equalTyped(E a, E b) {
        return codec.typedEquals(getter.apply(a), getter.apply(b));
    }
}
