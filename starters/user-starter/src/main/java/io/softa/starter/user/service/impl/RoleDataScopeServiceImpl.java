package io.softa.starter.user.service.impl;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.service.RoleDataScopeService;

/**
 * RoleDataScope Model Service Implementation. Publishes
 * {@code RoleGrantChangedEvent} on every write (see
 * {@link AbstractRoleGrantServiceImpl}).
 */
@Service
public class RoleDataScopeServiceImpl
        extends AbstractRoleGrantServiceImpl<RoleDataScope>
        implements RoleDataScopeService {

    private static final String ROLE_ID_FIELD =
            LambdaUtils.getAttributeName(RoleDataScope::getRoleId);

    public RoleDataScopeServiceImpl(ApplicationEventPublisher events) {
        super(events);
    }

    @Override
    protected Long roleIdOf(RoleDataScope entity) {
        return entity == null ? null : entity.getRoleId();
    }

    @Override
    protected String roleIdField() {
        return ROLE_ID_FIELD;
    }
}
