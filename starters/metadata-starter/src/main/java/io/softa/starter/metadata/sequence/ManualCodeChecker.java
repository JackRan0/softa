package io.softa.starter.metadata.sequence;

import java.util.ArrayList;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaIndex;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.sequence.exception.SequenceNotFoundException;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.service.SysSequenceService;

/**
 * Checks codes typed by hand into a sequence-numbered field: the fixed rules, then the format the
 * sequence configures ({@link ManualCodeRules}). Shared by the write validator and the live check a
 * form calls on blur, so a form cannot accept what the save refuses.
 *
 * <p>Only a code that is new or changed is checked; the caller decides that. A field with no sequence
 * row gets the fixed rules only.
 */
@Slf4j
@Component
public class ManualCodeChecker {

    private final SysSequenceService sysSequenceService;
    private final CompanyAbbreviationResolver companyAbbreviationResolver;
    private final SequenceCodeOccupancy occupancy;
    private final ModelService<Serializable> modelService;

    public ManualCodeChecker(SysSequenceService sysSequenceService,
                             CompanyAbbreviationResolver companyAbbreviationResolver,
                             SequenceCodeOccupancy occupancy,
                             @Lazy ModelService<Serializable> modelService) {
        this.sysSequenceService = sysSequenceService;
        this.companyAbbreviationResolver = companyAbbreviationResolver;
        this.occupancy = occupancy;
        this.modelService = modelService;
    }

    /**
     * The live check a form runs on blur. Answers what the save would: nothing for a blank code
     * (generated) or for the record's own stored code (unchanged), else the first rule it breaks.
     */
    public ManualCodeCheckResult checkTyped(ManualCodeCheckRequest request) {
        String code = typed(request.value());
        int dot = request.code() == null ? -1 : request.code().lastIndexOf('.');
        if (code == null || dot <= 0) {
            return new ManualCodeCheckResult(null);
        }
        String modelName = request.code().substring(0, dot);
        String field = request.code().substring(dot + 1);
        MetaField metaField = ModelManager.existModel(modelName) ? ModelManager.getModelFieldOrNull(modelName, field) : null;
        if (metaField == null || !metaField.isAutoSequence()) {
            return new ManualCodeCheckResult(null);
        }
        String stored = StringUtils.isBlank(request.recordId()) ? null : storedCode(modelName, field, request.recordId());
        if (code.equals(stored)) {
            return new ManualCodeCheckResult(null);
        }
        return new ManualCodeCheckResult(checkOne(modelName, field, code, idsOf(request.row()), stored));
    }

    private String storedCode(String modelName, String field, String recordId) {
        FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, field), Filters.of(ModelConstant.ID, Operator.EQUAL, recordId));
        query.setFilterControl(FilterControl.bypassAll());
        List<Map<String, Object>> rows = modelService.searchList(modelName, query);
        return rows.isEmpty() ? null : typed(rows.getFirst().get(field));
    }

    /** Form values carry references as {@code {id, displayName}}; the company lookup reads ids. */
    private static Map<String, Object> idsOf(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>();
        if (row != null) {
            row.forEach((key, value) -> out.put(key, value instanceof Map<?, ?> ref && ref.containsKey(ModelConstant.ID)
                    ? ref.get(ModelConstant.ID) : value));
        }
        return out;
    }

    /**
     * Check codes for a batch of records — one query for the duplicates, one per hop for the company
     * abbreviations, whatever the batch size. Two records of the batch typing the same code (ignoring
     * case): the second is the duplicate.
     *
     * @param modelName the model being written
     * @param field     the sequence-numbered field
     * @param codes     the typed codes, trimmed; null where a record has nothing to check
     * @param rows      the records, aligned with {@code codes} — read for the company abbreviation
     * @param ownCodes  each record's stored code (update), aligned; null entries on create. Typing back
     *                  one's own code in another case is not a duplicate.
     * @return one message per record, null where it passes
     */
    public List<String> check(String modelName, String field, List<String> codes, List<Map<String, Object>> rows,
                              List<String> ownCodes) {
        String[] messages = new String[codes.size()];
        String label = labelOf(modelName, field);
        String sequenceCode = modelName + "." + field;

        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            if (StringUtils.isEmpty(code)) {
                continue;
            }
            messages[i] = ManualCodeRules.spaces(label, code);
            if (messages[i] == null) {
                candidates.add(code);
            }
        }
        if (candidates.isEmpty()) {
            return Arrays.asList(messages);
        }

        String duplicate = duplicateMessage(modelName, field, label);
        Set<String> taken = duplicate == null ? Set.of() : occupancy.taken(sequenceCode, candidates);
        Set<String> seen = new HashSet<>();
        for (int i = 0; duplicate != null && i < codes.size(); i++) {
            String code = codes.get(i);
            if (StringUtils.isEmpty(code) || messages[i] != null) {
                continue;
            }
            String key = code;
            String own = ownCodes == null ? null : ownCodes.get(i);
            boolean ownCode = own != null && own.equals(code);
            if ((taken.contains(key) && !ownCode) || !seen.add(key)) {
                messages[i] = duplicate;
            }
        }

        ManualEntryRule rule = null;
        String template = null;
        SysSequence config = configOf(sequenceCode);
        if (config != null) {
            rule = config.getManualEntry();
            template = config.getTemplate();
        }
        if (rule == null || rule.effectiveFormat() == ManualEntryFormat.ANY) {
            return Arrays.asList(messages);
        }
        List<String> abbreviations = rule.effectiveFormat() == ManualEntryFormat.SAME_AS_AUTO_GENERATION
                && TemplateRenderer.usesCompanyAbbr(template)
                ? companyAbbreviationResolver.abbreviationsOf(modelName, rows)
                : null;
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            if (StringUtils.isEmpty(code) || messages[i] != null) {
                continue;
            }
            String abbreviation = abbreviations == null ? null : abbreviations.get(i);
            messages[i] = ManualCodeRules.format(label, code, rule, template, abbreviation);
        }
        return Arrays.asList(messages);
    }

    /** Check one code; see {@link #check(String, String, List, List, List)}. */
    public String checkOne(String modelName, String field, String code, Map<String, Object> row, String ownCode) {
        List<String> own = new ArrayList<>(1);
        own.add(ownCode);
        return check(modelName, field, List.of(code), List.of(row), own).getFirst();
    }

    /** The trimmed code, null when blank — a blank code is generated, never typed. */
    public static String typed(Object value) {
        return value instanceof CharSequence cs && !StringUtils.isBlank(cs) ? cs.toString().trim() : null;
    }

    private SysSequence configOf(String sequenceCode) {
        try {
            return sysSequenceService.loadConfigByCode(sequenceCode);
        } catch (SequenceNotFoundException e) {
            return null;
        }
    }

    private static String labelOf(String modelName, String field) {
        MetaField metaField = ModelManager.getModelFieldOrNull(modelName, field);
        return metaField == null || StringUtils.isBlank(metaField.getLabel()) ? field : metaField.getLabel();
    }

    /**
     * The message for a duplicate of this field, or null when uniqueness is not judged here.
     *
     * <p>A code unique across the tenant (a unique index on it, or on it and the tenant) is checked
     * exactly as spelled, as the index compares it — codes that differ only in case are different
     * codes — and with the index's own message, "An employee with this code already exists.", so a
     * duplicate reads the same whether this check or the database catches it. A field with no unique
     * index is checked across the tenant too, with a generic message. A code unique only within a scope
     * (a pay group's items, a country's parameters) is left to its index: telling apart another scope's
     * code from a duplicate needs that scope, and such codes are the system's, not typed.
     */
    private static String duplicateMessage(String modelName, String field, String label) {
        String message = label + " already exists.";
        for (MetaIndex index : ModelManager.getModelIndexes(modelName)) {
            List<String> fields = index.getIndexFields();
            if (!Boolean.TRUE.equals(index.getUniqueIndex()) || fields == null || !fields.contains(field)) {
                continue;
            }
            if (!fields.stream().allMatch(f -> Objects.equals(f, field) || TENANT_ID.equals(f))) {
                return null;
            }
            if (StringUtils.isNotBlank(index.getMessage())) {
                message = index.getMessage();
            }
        }
        return message;
    }

    private static final String TENANT_ID = "tenantId";
}
