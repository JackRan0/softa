package io.softa.starter.metadata.sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * Codes typed by hand into a sequence-numbered field are checked at the write root, so every way in —
 * a form, an import, the generic model API, a custom endpoint — gets the same rules.
 *
 * <ul>
 *   <li><b>create</b>: a non-blank code is typed and checked; a blank one is generated and is not.</li>
 *   <li><b>update</b>: only a changed code is checked. Sending the stored code back — a form posting
 *       the whole row, an import updating the record it matched by code — is not a change, so a code
 *       that predates the current rules is never re-judged.</li>
 * </ul>
 *
 * <p>Seeding and other system maintenance run with {@code skipPermissionCheck} and are not checked:
 * their codes are the system's own.
 */
@Component
@Order(150)
public class ManualCodeWriteValidator implements ModelWriteValidator {

    private static final String MESSAGES = ManualCodeWriteValidator.class.getName() + ".messages.";

    private final ManualCodeChecker checker;
    private final ConcurrentMap<String, List<String>> fieldsByModel = new ConcurrentHashMap<>();

    public ManualCodeWriteValidator(ManualCodeChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean supports(String modelName) {
        return !sequenceFields(modelName).isEmpty();
    }

    @Override
    public void validateBatch(String modelName, List<Map<String, Object>> rows, AccessType accessType,
                              Map<String, Object> scratch) {
        if (accessType != AccessType.CREATE || isSystemPath()) {
            return;
        }
        for (String field : sequenceFields(modelName)) {
            List<String> codes = new ArrayList<>(rows.size());
            boolean any = false;
            for (Map<String, Object> row : rows) {
                String code = ManualCodeChecker.typed(row.get(field));
                codes.add(code);
                any |= code != null;
            }
            if (any) {
                scratch.put(MESSAGES + field, checker.check(modelName, field, codes, rows, null));
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void validateCreate(WriteContext ctx) {
        if (isSystemPath()) {
            return;
        }
        for (String field : sequenceFields(ctx.modelName())) {
            List<String> messages = (List<String>) ctx.scratch().get(MESSAGES + field);
            if (messages != null && messages.get(ctx.rowIndex()) != null) {
                ctx.reject(field, messages.get(ctx.rowIndex()));
            }
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (isSystemPath()) {
            return;
        }
        for (String field : sequenceFields(ctx.modelName())) {
            if (!ctx.patch().containsKey(field)) {
                continue;
            }
            String code = ManualCodeChecker.typed(ctx.patch().get(field));
            String stored = ctx.originalRow() == null ? null : ManualCodeChecker.typed(ctx.originalRow().get(field));
            if (code == null || Objects.equals(code, stored)) {
                continue;
            }
            // The merged row: a company abbreviation follows the legal entity the same save sets.
            String message = checker.checkOne(ctx.modelName(), field, code, ctx.row(), stored);
            if (message != null) {
                ctx.reject(field, message);
            }
        }
    }

    private List<String> sequenceFields(String modelName) {
        return fieldsByModel.computeIfAbsent(modelName, name -> {
            if (!ModelManager.existModel(name)) {
                return List.of();
            }
            return ModelManager.getModelFields(name).stream()
                    .filter(MetaField::isAutoSequence)
                    .map(MetaField::getFieldName)
                    .toList();
        });
    }

    private static boolean isSystemPath() {
        Context context = ContextHolder.getContext();
        return context != null && context.isSkipPermissionCheck();
    }
}
