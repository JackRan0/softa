package io.softa.starter.user.scope;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.enums.ScopeType;

/**
 * Single source of truth for "which {@link ScopeType}s can apply to a
 * given model". A pure dispatcher: iterates every registered
 * {@link ScopeContributor} bean and asks each whether its scope is
 * applicable via {@link ScopeContributor#isApplicableTo}.
 *
 * <h3>Consumed by</h3>
 * <ul>
 *   <li>{@code NavigationConfigOptionsController} — tells the FE wizard
 *       which scope checkboxes to enable per nav.</li>
 *   <li>{@link ScopeRuleCompiler} — fail-fast on rules whose scope is
 *       inapplicable (e.g. DEPT_SUBTREE on a model with no
 *       {@code departmentId} field and no cascade path to one).</li>
 * </ul>
 *
 * <h3>Domain-agnostic by design</h3>
 * Nothing here mentions any business entity. Model-identity special
 * cases (e.g. SELF on the {@code Employee} model matching on {@code id}
 * instead of {@code employeeId}) live inside the HR-domain-owned
 * contributors' {@link ScopeContributor#isApplicableTo} overrides — the
 * framework knows only about the SPI.
 */
@Component
public class ScopeApplicabilityResolver {

    private final List<ScopeContributor> contributors;

    public ScopeApplicabilityResolver(List<ScopeContributor> contributors) {
        this.contributors = contributors;
    }

    /**
     * @param modelName e.g. {@code "Employee"} / {@code "LeaveRequest"} —
     *                  PascalCase, matches {@code MetaModel.modelName}
     * @return the {@link ScopeType}s applicable to this model.
     *         {@link ScopeType#ALL} is always present; every other type
     *         is included iff its contributor's
     *         {@link ScopeContributor#isApplicableTo} returns true.
     */
    public Set<ScopeType> applicableFor(String modelName) {
        EnumSet<ScopeType> out = EnumSet.of(ScopeType.ALL);
        if (modelName == null || !ModelManager.existModel(modelName)) return out;

        Set<String> fieldNames = new HashSet<>();
        for (MetaField mf : ModelManager.getModelFields(modelName)) {
            fieldNames.add(mf.getFieldName());
        }

        for (ScopeContributor c : contributors) {
            if (c.isApplicableTo(modelName, fieldNames)) {
                out.add(c.scopeType());
            }
        }
        return out;
    }
}
