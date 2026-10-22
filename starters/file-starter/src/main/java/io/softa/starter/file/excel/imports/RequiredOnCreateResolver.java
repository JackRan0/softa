package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

/**
 * Settles the template-required columns a row left blank, once it is known whether the row creates a
 * record or updates one.
 *
 * <p>With {@code ignoreEmpty} on, a blank cell is dropped from the row, which on an update means "keep
 * the stored value". A cell-level check cannot tell the two apart, so a file of corrections that
 * touched one column had to restate every required column of every row. The field handlers therefore
 * only <i>note</i> the blank required columns on the row (under {@link #MARKER}) when {@code ignoreEmpty}
 * is on, and this step turns the note into the usual "The field `X` is required" failure for a row that
 * creates, and drops it for a row that updates.
 *
 * <p>Create or update is decided the way the write will decide it — {@code ModelService.splitByExistence}
 * on the template's unique key, including its case-insensitive code matching — run on copies, because
 * it writes the matched ids into the rows it is given. Under {@code ONLY_CREATE} every row creates, so
 * nothing is queried. A row whose key is incomplete cannot match anything and creates. A row that has
 * already failed for another reason (an in-file duplicate, a bad value) is not looked up — its key may
 * be the very thing that is wrong — and keeps only its existing reason; the note is dropped.
 *
 * <p>The note never outlives this step: it is removed from every row whatever the outcome, so it
 * reaches neither the custom handler, the write, nor the result file.
 */
@Component
public class RequiredOnCreateResolver {

    /** Row key under which a field handler notes the headers of blank required columns. */
    public static final String MARKER = "__missingRequiredHeaders";

    private static final String REQUIRED_MESSAGE = "The field `{0}` is required";

    @Autowired
    private ModelService<?> modelService;

    /** Note on the row that the required column {@code header} was left blank. */
    @SuppressWarnings("unchecked")
    public static void record(Map<String, Object> row, String header) {
        ((Set<String>) row.computeIfAbsent(MARKER, k -> new LinkedHashSet<String>())).add(header);
    }

    /**
     * Fail the creating rows that left a required column blank, and clear the note from every row.
     *
     * @param template      the import template
     * @param rows          the rows, after field handling, lookup resolution and duplicate marking
     * @param skipException false to throw on the first creating row with a blank required column
     */
    public void resolve(ImportTemplateDTO template, List<Map<String, Object>> rows, boolean skipException) {
        List<Map<String, Object>> noted = rows.stream().filter(row -> row.containsKey(MARKER)).toList();
        if (noted.isEmpty()) {
            return;
        }
        Set<Map<String, Object>> creating = creatingRows(template, noted);
        for (Map<String, Object> row : noted) {
            @SuppressWarnings("unchecked")
            Set<String> headers = (Set<String>) row.remove(MARKER);
            if (!creating.contains(row)) {
                continue;
            }
            if (!skipException) {
                throw new ValidationException(REQUIRED_MESSAGE, headers.iterator().next());
            }
            for (String header : headers) {
                Object previous = row.get(FileConstant.FAILED_REASON);
                // Worded exactly as the handler's own check words it.
                String message = new ValidationException(REQUIRED_MESSAGE, header).getMessage();
                row.put(FileConstant.FAILED_REASON, previous == null ? message : previous + "; " + message);
            }
        }
    }

    /** The noted rows that create a record, by identity. */
    private Set<Map<String, Object>> creatingRows(ImportTemplateDTO template, List<Map<String, Object>> noted) {
        Set<Map<String, Object>> creating = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> keys = template.getUniqueConstraints();
        ImportRule rule = template.getImportRule();
        boolean canMatch = (ImportRule.CREATE_OR_UPDATE.equals(rule) || ImportRule.ONLY_UPDATE.equals(rule))
                && !CollectionUtils.isEmpty(keys);
        Map<Map<String, Object>, Map<String, Object>> originalOfCopy = new IdentityHashMap<>();
        for (Map<String, Object> row : noted) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            if (!canMatch || !hasCompleteKey(row, keys)) {
                creating.add(row);
                continue;
            }
            Map<String, Object> copy = ImportPersistenceService.copyRows(List.of(row)).getFirst();
            copy.remove(MARKER);
            originalOfCopy.put(copy, row);
        }
        if (!originalOfCopy.isEmpty()) {
            CreateOrUpdateResult split = modelService.splitByExistence(
                    template.getModelName(), new ArrayList<>(originalOfCopy.keySet()), keys);
            split.created().forEach(copy -> creating.add(originalOfCopy.get(copy)));
        }
        return creating;
    }

    private static boolean hasCompleteKey(Map<String, Object> row, List<String> keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null || (value instanceof String text && StringUtils.isBlank(text))) {
                return false;
            }
        }
        return true;
    }
}
