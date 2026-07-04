package io.softa.framework.orm.enums;

/**
 * Filter type:
 * <ul>
 *   <li>{@code EMPTY} — no predicate; dropped by combine/WhereBuilder.</li>
 *   <li>{@code TREE} — logical AND/OR of children.</li>
 *   <li>{@code LEAF} — single {@code FilterUnit}.</li>
 * </ul>
 *
 * <p>A "match no rows" condition is expressed as an ordinary {@code LEAF}
 * (empty-tuple {@code IN}) that renders {@code 1=0} — no dedicated type.
 */
public enum FilterType {
    EMPTY, TREE, LEAF
}
