package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Purges finished Automation runs once their retention period ({@code flow.automation.retention-days},
 * default 30) has passed: the instances with their trace and parallel-branch rows, and the trigger event
 * log of the same age. An automation run is an operational record, kept for days; an approval is the
 * record's approval history and is never purged here, nor is a run still waiting or running.
 */
@Slf4j
@Service
public class FlowAutomationPurgeService {

    private static final List<FlowExecutionStatus> FINISHED = Arrays.stream(FlowExecutionStatus.values())
            .filter(FlowExecutionStatus::isTerminal)
            .toList();

    private static final List<String> INSTANCE_KEYS = List.of(
            LambdaUtils.getAttributeName(FlowInstance::getId),
            LambdaUtils.getAttributeName(FlowInstance::getInstanceId));

    private final FlowInstanceServiceImpl instanceService;
    private final FlowEventServiceImpl eventService;
    private final FlowInstancePurger instancePurger;

    @Value("${flow.automation.retention-days:30}")
    private int retentionDays;

    public FlowAutomationPurgeService(FlowInstanceServiceImpl instanceService,
                                      FlowEventServiceImpl eventService,
                                      FlowInstancePurger instancePurger) {
        this.instanceService = instanceService;
        this.eventService = eventService;
        this.instancePurger = instancePurger;
    }

    /**
     * Purge expired runs and trigger events in batches of {@code batchSize}, at most {@code maxBatches}
     * of each per call; a backlog larger than that is left for the next call.
     */
    public void purgeExpiredRuns(int batchSize, int maxBatches) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int runs = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            FlexQuery query = new FlexQuery(new Filters()
                    .eq(FlowInstance::getScenario, FlowScenario.AUTOMATION)
                    .in(FlowInstance::getStatus, FINISHED)
                    .lt(FlowInstance::getUpdatedTime, cutoff));
            query.select(INSTANCE_KEYS);
            query.setLimitSize(batchSize);
            List<FlowInstance> expired = instanceService.searchList(query);
            instancePurger.delete(expired);
            runs += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        int events = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            FlexQuery query = new FlexQuery(new Filters().lt(FlowEvent::getEventTime, cutoff));
            query.select(List.of(LambdaUtils.getAttributeName(FlowEvent::getId)));
            query.setLimitSize(batchSize);
            List<Long> expired = eventService.searchList(query).stream().map(FlowEvent::getId).toList();
            if (!expired.isEmpty()) {
                eventService.deleteByIds(expired);
            }
            events += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        if (runs > 0 || events > 0) {
            log.info("Flow automation purge: removed {} run(s) and {} trigger event(s) older than {} day(s)",
                    runs, events, retentionDays);
        }
    }
}
