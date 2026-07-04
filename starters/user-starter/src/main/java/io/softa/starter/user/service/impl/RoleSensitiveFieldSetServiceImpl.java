package io.softa.starter.user.service.impl;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;

/**
 * RoleSensitiveFieldSet Model Service Implementation. Publishes
 * {@code RoleGrantChangedEvent} on every write (see
 * {@link AbstractRoleGrantServiceImpl}).
 */
@Service
public class RoleSensitiveFieldSetServiceImpl
        extends AbstractRoleGrantServiceImpl<RoleSensitiveFieldSet>
        implements RoleSensitiveFieldSetService {

    private static final String ROLE_ID_FIELD =
            LambdaUtils.getAttributeName(RoleSensitiveFieldSet::getRoleId);

    public RoleSensitiveFieldSetServiceImpl(ApplicationEventPublisher events) {
        super(events);
    }

    @Override
    protected Long roleIdOf(RoleSensitiveFieldSet entity) {
        return entity == null ? null : entity.getRoleId();
    }

    @Override
    protected String roleIdField() {
        return ROLE_ID_FIELD;
    }
}
