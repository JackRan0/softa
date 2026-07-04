package io.softa.starter.studio.meta.service.impl;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.meta.service.DesignOptionItemService;
import io.softa.starter.studio.meta.service.DesignOptionSetService;

/**
 * DesignOptionSet Model Service Implementation
 */
@Service
public class DesignOptionSetServiceImpl extends EntityServiceImpl<DesignOptionSet, Long> implements DesignOptionSetService {

    @Autowired
    private DesignOptionItemService optionItemService;

    /**
     * Cascade-delete the option-set's children: deleting a {@link DesignOptionSet} also removes its
     * {@link DesignOptionItem} rows, so the no-code lane never leaves orphan items (an item whose
     * parent option-set is gone) in the env's {@code design_*} workspace.
     *
     * <p>The ORM does not cascade {@code ONE_TO_MANY} deletes and there is no DB foreign key on the
     * item {@code option_set_id}, so without this the items would accumulate as garbage — the
     * publish/merge differ already excludes such orphans, but never cleaned them up.
     *
     * <p>Items are matched by the rename-stable surrogate FK {@code optionSetId} — the same join the
     * {@code DesignOptionSet.optionItems} relation uses. {@code optionSetId} is a globally unique
     * distributed id, so {@code optionSetId IN (parentIds)} is inherently scoped to each parent's own
     * env and can never reach another env's rows. Items drop before the parent, and the whole
     * operation is one transaction so a failure rolls back cleanly.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        cascadeDeleteItems(ids);
        return super.deleteByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        return this.deleteByIds(Collections.singletonList(id));
    }

    private void cascadeDeleteItems(List<Long> optionSetIds) {
        if (CollectionUtils.isEmpty(optionSetIds)) {
            return;
        }
        optionItemService.deleteByFilters(new Filters().in(DesignOptionItem::getOptionSetId, optionSetIds));
    }

}
