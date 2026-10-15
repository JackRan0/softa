package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.dto.UserListViewPreferenceDTO;
import io.softa.starter.user.entity.UserListViewPreference;
import io.softa.starter.user.service.UserListViewPreferenceService;

@Service
public class UserListViewPreferenceServiceImpl extends EntityServiceImpl<UserListViewPreference, Long>
        implements UserListViewPreferenceService {

    static final String PART_COLUMNS = "columns";
    static final String PART_EXPORT = "export";
    static final String PART_SETTINGS = "settings";

    /** Generous for any real list, small enough that a row cannot be used as storage. */
    static final int MAX_NAMES = 500;
    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_VIEW_STATE_KEY_LENGTH = 255;
    /** View settings are a handful of flags; this is ample, and keeps a row from becoming storage. */
    static final int MAX_SETTINGS_LENGTH = 4000;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<String>>> STRING_LIST_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    @Autowired
    private PermissionService permissionService;

    @Override
    public UserListViewPreferenceDTO getMine(String viewStateKey) {
        requireViewStateKey(viewStateKey);
        UserListViewPreference row = asSystem(() -> findMine(viewStateKey));
        // Filtered in the caller's own context, not the system's: under a skipped permission check
        // every field reads as allowed, which is exactly the pass this filter exists to deny.
        return row == null ? null : readable(toDto(row));
    }

    /**
     * Not one transaction, on purpose. Two parts of a list can save their first arrangement at once —
     * the side tree and the columns on the same page load — and both find no row and create one. The
     * loser meets the unique index; in its own statement it can then find the winner's row and save
     * over it, which inside a shared transaction the database would no longer allow.
     */
    @Override
    public UserListViewPreferenceDTO saveMine(UserListViewPreferenceDTO preference) {
        if (preference == null) {
            throw new BusinessException("Nothing to save.");
        }
        requireViewStateKey(preference.getViewStateKey());
        String model = preference.getModelName();
        if (model == null || !ModelManager.existModel(model)) {
            throw new BusinessException("Unknown model: " + model);
        }
        // What the person may not read is not kept either: an arrangement is a list of what they
        // see, and storing a withdrawn field would only bring it back if the grant ever returned.
        UserListViewPreferenceDTO incoming = readable(bounded(preference));
        Long me = currentUserId();
        asSystem(() -> {
            UserListViewPreference existing = findMine(incoming.getViewStateKey());
            if (existing != null) {
                this.updateOne(applied(existing, incoming));
                return null;
            }
            UserListViewPreference created = new UserListViewPreference();
            created.setUserId(me);
            created.setViewStateKey(incoming.getViewStateKey());
            try {
                this.createOne(applied(created, incoming));
            } catch (RuntimeException raced) {
                UserListViewPreference winner = findMine(incoming.getViewStateKey());
                if (winner == null) {
                    throw raced;
                }
                this.updateOne(applied(winner, incoming));
            }
            return null;
        });
        return getMine(incoming.getViewStateKey());
    }

    @Override
    public void resetMine(String viewStateKey, String part) {
        requireViewStateKey(viewStateKey);
        boolean columns = part == null || PART_COLUMNS.equals(part);
        boolean export = part == null || PART_EXPORT.equals(part);
        boolean settings = part == null || PART_SETTINGS.equals(part);
        if (!columns && !export && !settings) {
            throw new BusinessException("Unknown part: " + part);
        }
        asSystem(() -> {
            UserListViewPreference row = findMine(viewStateKey);
            if (row == null) {
                return null;
            }
            if (columns) {
                row.setColumnOrder(null);
                row.setHiddenColumns(null);
                row.setFreezeColumnIndex(null);
            }
            if (export) {
                row.setExportFields(null);
                row.setExportObjectFields(null);
            }
            if (settings) {
                row.setViewSettings(null);
            }
            if (isEmpty(row)) {
                this.deleteById(row.getId());
            } else {
                this.updateOne(row, false);
            }
            return null;
        });
    }

    /** The caller's row for a list. Tenant is narrowed by the framework; the account by this filter. */
    private UserListViewPreference findMine(String viewStateKey) {
        List<UserListViewPreference> rows = this.searchList(new Filters()
                .eq(UserListViewPreference::getUserId, currentUserId())
                .eq(UserListViewPreference::getViewStateKey, viewStateKey));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** {@code row} with the parts {@code incoming} carries laid over it; the parts it omits kept. */
    private static UserListViewPreference applied(UserListViewPreference row, UserListViewPreferenceDTO incoming) {
        row.setModelName(incoming.getModelName());
        if (incoming.getColumnOrder() != null) row.setColumnOrder(JsonUtils.objectToString(incoming.getColumnOrder()));
        if (incoming.getHiddenColumns() != null) row.setHiddenColumns(JsonUtils.objectToString(incoming.getHiddenColumns()));
        if (incoming.getFreezeColumnIndex() != null) row.setFreezeColumnIndex(Math.max(0, incoming.getFreezeColumnIndex()));
        if (incoming.getExportFields() != null) row.setExportFields(JsonUtils.objectToString(incoming.getExportFields()));
        if (incoming.getExportObjectFields() != null) {
            row.setExportObjectFields(JsonUtils.objectToString(incoming.getExportObjectFields()));
        }
        if (incoming.getViewSettings() != null) {
            row.setViewSettings(mergeSettings(row.getViewSettings(), incoming.getViewSettings()));
        }
        return row;
    }

    private static boolean isEmpty(UserListViewPreference row) {
        return row.getColumnOrder() == null && row.getHiddenColumns() == null && row.getFreezeColumnIndex() == null
                && row.getExportFields() == null && row.getExportObjectFields() == null
                && row.getViewSettings() == null;
    }

    /**
     * Drop every field the caller may not read. The list's own fields are checked against its
     * model; each object's fields against the model that object is.
     */
    UserListViewPreferenceDTO readable(UserListViewPreferenceDTO dto) {
        String model = dto.getModelName();
        if (model == null || !ModelManager.existModel(model)) {
            return dto;
        }
        dto.setColumnOrder(readableOf(model, dto.getColumnOrder()));
        dto.setHiddenColumns(readableOf(model, dto.getHiddenColumns()));
        dto.setExportFields(readableOf(model, dto.getExportFields()));
        Map<String, List<String>> objects = dto.getExportObjectFields();
        if (objects != null) {
            Map<String, List<String>> kept = new LinkedHashMap<>();
            for (String collection : readableOf(model, new ArrayList<>(objects.keySet()))) {
                String related = relatedModelOf(model, collection);
                if (related != null) {
                    kept.put(collection, readableOf(related, objects.get(collection)));
                }
            }
            dto.setExportObjectFields(kept);
        }
        return dto;
    }

    private List<String> readableOf(String model, List<String> names) {
        if (names == null) {
            return null;
        }
        Collection<String> readable = permissionService.filterReadableFields(model, names, AccessType.READ);
        return readable == null ? names : new ArrayList<>(readable);
    }

    private static String relatedModelOf(String model, String field) {
        if (!ModelManager.existField(model, field)) {
            return null;
        }
        MetaField meta = ModelManager.getModelField(model, field);
        return meta == null ? null : meta.getRelatedModel();
    }

    /**
     * The stored settings with {@code incoming} laid over them key by key; a key sent as null is
     * removed. Null when nothing is left, so an emptied row can be deleted.
     */
    static String mergeSettings(String stored, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (stored != null) {
            Map<String, Object> existing = JsonUtils.stringToObject(stored, OBJECT_MAP, null);
            if (existing != null) merged.putAll(existing);
        }
        incoming.forEach((key, value) -> {
            if (value == null) merged.remove(key);
            else merged.put(key, value);
        });
        if (merged.isEmpty()) {
            return null;
        }
        String json = JsonUtils.objectToString(merged);
        if (json.length() > MAX_SETTINGS_LENGTH) {
            throw new BusinessException("The list's view settings are too large.");
        }
        return json;
    }

    /** Refuse what no real list produces, so a row cannot be used to store anything else. */
    private static UserListViewPreferenceDTO bounded(UserListViewPreferenceDTO dto) {
        checkNames(dto.getColumnOrder());
        checkNames(dto.getHiddenColumns());
        checkNames(dto.getExportFields());
        if (dto.getViewSettings() != null) {
            dto.getViewSettings().keySet().forEach(UserListViewPreferenceServiceImpl::checkName);
        }
        if (dto.getExportObjectFields() != null) {
            if (dto.getExportObjectFields().size() > MAX_NAMES) {
                throw new BusinessException("Too many objects in the arrangement.");
            }
            dto.getExportObjectFields().forEach((key, value) -> {
                checkName(key);
                checkNames(value);
            });
        }
        return dto;
    }

    private static void checkNames(List<String> names) {
        if (names == null) {
            return;
        }
        if (names.size() > MAX_NAMES) {
            throw new BusinessException("Too many fields in the arrangement.");
        }
        names.forEach(UserListViewPreferenceServiceImpl::checkName);
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("Invalid field name in the arrangement.");
        }
    }

    private static UserListViewPreferenceDTO toDto(UserListViewPreference row) {
        UserListViewPreferenceDTO dto = new UserListViewPreferenceDTO();
        dto.setViewStateKey(row.getViewStateKey());
        dto.setModelName(row.getModelName());
        dto.setColumnOrder(parseList(row.getColumnOrder()));
        dto.setHiddenColumns(parseList(row.getHiddenColumns()));
        dto.setFreezeColumnIndex(row.getFreezeColumnIndex());
        dto.setExportFields(parseList(row.getExportFields()));
        dto.setExportObjectFields(row.getExportObjectFields() == null ? null
                : JsonUtils.stringToObject(row.getExportObjectFields(), STRING_LIST_MAP, null));
        dto.setViewSettings(row.getViewSettings() == null ? null
                : JsonUtils.stringToObject(row.getViewSettings(), OBJECT_MAP, null));
        return dto;
    }

    private static List<String> parseList(String json) {
        return json == null ? null : JsonUtils.stringToObject(json, STRING_LIST, null);
    }

    private static void requireViewStateKey(String viewStateKey) {
        if (viewStateKey == null || viewStateKey.isBlank() || viewStateKey.length() > MAX_VIEW_STATE_KEY_LENGTH) {
            throw new BusinessException("A list key is required.");
        }
    }

    private static Long currentUserId() {
        Long id = ContextHolder.getContext().getUserId();
        if (id == null) {
            throw new BusinessException("Sign in to keep a list arrangement.");
        }
        return id;
    }

    /**
     * Past the row scope: the model has no anchor a role could be scoped by, so an ordinary read
     * would fail closed. {@link #findMine} narrows to the caller's own rows instead.
     */
    private static <R> R asSystem(Supplier<R> action) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, action::get);
    }
}
