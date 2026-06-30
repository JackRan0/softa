package io.softa.starter.user.scope.contributor;

import java.util.List;

import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.scope.ScopeContributor;

/**
 * {@link ScopeType#CREATED_BY_SELF} — rows whose {@code createdId} matches
 * the current user's id. Driven by {@link Principal#getUserId()} (not by
 * any domain extension) — works for pure users (admin / external / bot)
 * too, not just employees.
 *
 * <p>{@code createdId} is on every AuditableModel descendant so the field
 * declaration is effectively universal.
 */
@Component
public class CreatedBySelfScopeContributor implements ScopeContributor {

    @Override
    public ScopeType scopeType() {
        return ScopeType.CREATED_BY_SELF;
    }

    @Override
    public List<String> applicableFields() {
        return List.of(ModelConstant.CREATED_ID);
    }

    @Override
    public Filters compile(ScopeRule rule, Principal principal, String modelName) {
        if (principal == null || principal.getUserId() == null) return new Filters();
        return Filters.of(ModelConstant.CREATED_ID, Operator.EQUAL, principal.getUserId());
    }
}
