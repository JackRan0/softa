package io.softa.starter.flow.message;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.cron.job.CronJob;
import io.softa.starter.cron.job.CronJobProvider;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.service.FlowBundleService;

/**
 * Puts the Cron-triggered flows on the cron schedule: one job per flow whose active revision has a
 * {@link CronTrigger}, read from the bundles every time the scheduler refreshes — so publishing, activating
 * another revision or changing the trigger moves the schedule with it, and nothing else holds a copy of it.
 * <p>
 * A job is named after the revision it schedules ({@link #jobName}), runs in the flow's tenant, and a
 * platform-shared flow's runs once in every active tenant; without multi-tenancy, every job runs once with no
 * tenant. {@link CronTaskFlowConsumer} starts the revision its messages name, and only while that is still its
 * flow's active revision.
 */
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CronFlowJobProvider implements CronJobProvider {

    /** Cron name of a flow's job: this prefix, then the id of the revision it schedules. */
    static final String JOB_NAME_PREFIX = "flow:";

    private final FlowBundleService bundleService;

    public CronFlowJobProvider(FlowBundleService bundleService) {
        this.bundleService = bundleService;
    }

    @Override
    public List<CronJob> listCronJobs() {
        boolean multiTenancy = SystemConfig.env.isEnableMultiTenancy();
        // Every tenant's flows, whatever thread the scheduler reads them on
        return ContextUtils.inSystemContext(bundleService::listActiveCronBundles).stream()
                .map(bundle -> toJob(bundle, multiTenancy))
                .toList();
    }

    private static CronJob toJob(FlowBundle bundle, boolean multiTenancy) {
        String name = jobName(bundle.getId());
        if (!multiTenancy) {
            // No tenant to run in: the flow runs once, as the deployment's own
            return CronJob.of(name, bundle.getCronExpression());
        }
        return FlowBundleRegistry.SHARED_TENANT_ID.equals(bundle.getTenantId())
                ? CronJob.perTenant(name, bundle.getCronExpression())
                : CronJob.ofTenant(name, bundle.getCronExpression(), bundle.getTenantId());
    }

    static String jobName(Long bundleId) {
        return JOB_NAME_PREFIX + bundleId;
    }

    /**
     * The revision a cron message schedules, or null when the message is not a flow's job: another module's,
     * or a {@code SysCron} row's — a flow's job is never a row, whatever its name.
     */
    static Long bundleIdOf(CronTaskMessage message) {
        String cronName = message.getCronName();
        if (message.getCronId() != null || cronName == null || !cronName.startsWith(JOB_NAME_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(cronName.substring(JOB_NAME_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
