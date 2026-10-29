package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.flow.entity.*;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;

/**
 * Purges expired debug-run bundles and everything they spawned: the bundle row,
 * its instances, their trace rows and parallel branches, and any approval tasks/records
 * those debug runs created (debug runs are not sandboxed — approvals land in real inboxes,
 * so their projections must go when the run is purged).
 *
 * <p>{@code FlowDebugHistory} rows are deliberately kept — they are the debug
 * log the editor lists.</p>
 */
@Slf4j
@Service
public class FlowDebugPurgeService {

    private final FlowBundleServiceImpl bundleService;
    private final FlowInstanceServiceImpl instanceService;
    private final FlowInstancePurger instancePurger;
    private final FlowBundleRegistry bundleRegistry;

    @Value("${flow.debug.retention-days:7}")
    private int retentionDays;

    public FlowDebugPurgeService(FlowBundleServiceImpl bundleService,
                                 FlowInstanceServiceImpl instanceService,
                                 FlowInstancePurger instancePurger,
                                 FlowBundleRegistry bundleRegistry) {
        this.bundleService = bundleService;
        this.instanceService = instanceService;
        this.instancePurger = instancePurger;
        this.bundleRegistry = bundleRegistry;
    }

    /**
     * Purge up to {@code limit} debug bundles created before the retention window.
     */
    public void purgeExpiredDebugBundles(int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        Filters filters = new Filters()
                .eq(FlowBundle::getDebug, true)
                .lt(FlowBundle::getCreatedTime, cutoff);
        FlexQuery query = new FlexQuery(filters);
        query.setLimitSize(limit);

        List<FlowBundle> expired = bundleService.searchList(query);
        for (FlowBundle bundle : expired) {
            purgeBundle(bundle);
        }
        if (!expired.isEmpty()) {
            log.info("Flow debug purge: removed {} debug bundle(s) older than {} day(s)",
                    expired.size(), retentionDays);
        }
    }

    private void purgeBundle(FlowBundle bundle) {
        instancePurger.delete(instanceService.searchList(
                new Filters().eq(FlowInstance::getBundleId, bundle.getId())));
        bundleService.deleteById(bundle.getId());
        bundleRegistry.evict(bundle.getId());
    }
}
