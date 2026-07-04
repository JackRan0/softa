package io.softa.framework.orm.domain;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Controls which built-in WHERE-clause filters are applied by `WhereBuilder`.
 *
 * Soft-delete and active-control filters are query-scope defaults that internal ORM machinery (id lookups,
 * relation expansion) may bypass without compromising authorization, which is enforced separately by `PermissionService`.
 */
@Data
@NoArgsConstructor
public class FilterControl implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean skipActiveControl = false;
    private boolean skipSoftDelete = false;

    public static FilterControl bypassAll() {
        FilterControl fc = new FilterControl();
        fc.skipActiveControl = true;
        fc.skipSoftDelete = true;
        return fc;
    }

    public static FilterControl bypassSoftDelete() {
        FilterControl fc = new FilterControl();
        fc.skipSoftDelete = true;
        return fc;
    }

    public static FilterControl bypassActiveControl() {
        FilterControl fc = new FilterControl();
        fc.skipActiveControl = true;
        return fc;
    }
}
