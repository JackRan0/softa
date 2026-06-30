package io.softa.starter.user.scope;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.enums.ScopeType;

/**
 * Single source of truth for "which {@link ScopeType}s can apply to a given
 * model". Reads the applicable-fields contract from every registered
 * {@link ScopeContributor} bean at startup, so this resolver stays in
 * sync with the contributor implementations without manual table edits.
 *
 * <p>Consulted by:
 * <ul>
 *   <li>{@code NavigationConfigOptionsController} — tells the FE wizard
 *       which scope checkboxes to enable per nav.</li>
 *   <li>{@link ScopeRuleCompiler} — fail-fast on rules whose scope is
 *       inapplicable (e.g. DEPT_SUBTREE on a model with no
 *       {@code departmentId} field).</li>
 * </ul>
 *
 * <h3>Special cases</h3>
 * <ul>
 *   <li>{@link ScopeType#ALL} and {@link ScopeType#CUSTOM} are always
 *       applicable — the contributor's {@code applicableFields()} is
 *       ignored for these.</li>
 *   <li>{@link #SELF_BY_PRIMARY_KEY} — models whose row identity IS an
 *       employee (today only the {@code Employee} model). For these:
 *       SELF compiles as {@code id = ec.employeeId} and DIRECT_REPORTS
 *       compiles as {@code piEmployeeId = ec.employeeId}. Listed here so
 *       the FE wizard and the Self/DirectReports contributors agree on
 *       which models qualify.</li>
 * </ul>
 */
@Component
public class ScopeApplicabilityResolver {

    /**
     * Models whose row identity IS an employee — see class javadoc.
     *
     * <p>Today this is a hard-coded set with the single known entry.
     * When a second employee-identity model appears (Contractor / Intern /
     * cross-company employee view), promote to a
     * {@code @ConfigurationProperties} bean keyed
     * {@code permission.scope.self-by-primary-key-models}.
     */
    private static final Set<String> SELF_BY_PRIMARY_KEY = Set.of("Employee");

    private final List<ScopeContributor> contributors;

    /** Built once in {@link #initIndex()} from contributor metadata. */
    private volatile Map<ScopeType, Set<String>> applicableFieldsByType = Map.of();

    public ScopeApplicabilityResolver(List<ScopeContributor> contributors) {
        this.contributors = contributors;
    }

    @PostConstruct
    void initIndex() {
        java.util.EnumMap<ScopeType, Set<String>> idx = new java.util.EnumMap<>(ScopeType.class);
        for (ScopeContributor c : contributors) {
            List<String> fields = c.applicableFields();
            if (fields == null || fields.isEmpty()) continue;
            idx.put(c.scopeType(), Set.copyOf(fields));
        }
        this.applicableFieldsByType = Map.copyOf(idx);
    }

    /**
     * @param modelName e.g. {@code "Employee"} / {@code "LeaveRequest"} —
     *                  PascalCase, matches {@code MetaModel.modelName}
     * @return applicable {@link ScopeType}s. {@code ALL} and {@code CUSTOM}
     *         are always present; the rest depend on the model having
     *         at least one of the contributor's declared
     *         {@link ScopeContributor#applicableFields()}.
     */
    public Set<ScopeType> applicableFor(String modelName) {
        EnumSet<ScopeType> out = EnumSet.of(ScopeType.ALL, ScopeType.CUSTOM);
        if (modelName == null || !ModelManager.existModel(modelName)) return out;

        Set<String> fieldNames = new HashSet<>();
        for (MetaField mf : ModelManager.getModelFields(modelName)) {
            fieldNames.add(mf.getFieldName());
        }

        for (Map.Entry<ScopeType, Set<String>> e : applicableFieldsByType.entrySet()) {
            for (String required : e.getValue()) {
                if (fieldNames.contains(required)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        if (SELF_BY_PRIMARY_KEY.contains(modelName)) {
            out.add(ScopeType.SELF);
            out.add(ScopeType.DIRECT_REPORTS);
        }
        return out;
    }

    /** True iff this model's row identity IS an employee. */
    public boolean isEmployeeModel(String modelName) {
        return modelName != null && SELF_BY_PRIMARY_KEY.contains(modelName);
    }
}
