package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * MetaIndex — in-memory representation of a {@code sys_model_index} row.
 *
 * <p>Loaded at metadata init into an independent, globally-keyed registry in
 * {@link ModelManager} (index name &rarr; index; NOT mounted under a model),
 * where index-name global uniqueness is enforced. Backs the friendly
 * duplicate-key message lookup (a violated index name resolves to its member
 * fields and optional custom {@link #message}).
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
public class MetaIndex implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String modelName;

    private String indexName;

    private List<String> indexFields;

    private Boolean uniqueIndex;

    /** End-user message shown when this unique constraint is violated (its own i18n key); null = none. */
    private String message;
}
