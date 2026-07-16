package io.softa.starter.flow.service.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Batch-enriches approval task views with their owning instance's business
 * context (title / bound model / row id / execution status) — ONE `IN` query
 * per page instead of an N+1 the frontend cannot even perform itself (the
 * runtime instance read is participant-scoped).
 */
@Component
public class TaskInstanceContextEnricher {

    private final FlowInstanceService instanceService;

    public TaskInstanceContextEnricher(FlowInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    public void enrich(List<FlowApprovalTaskView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        List<String> instanceIds = views.stream()
                .map(FlowApprovalTaskView::getInstanceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<String, FlowInstance> byInstanceId = instanceService.findByInstanceIds(instanceIds).stream()
                .collect(Collectors.toMap(FlowInstance::getInstanceId, Function.identity(), (a, b) -> a));
        for (FlowApprovalTaskView view : views) {
            FlowInstance instance = byInstanceId.get(view.getInstanceId());
            if (instance == null) {
                // Retention cleanup may have removed the instance — the task row stands alone.
                continue;
            }
            view.setInstanceTitle(instance.getTitle());
            view.setModelName(instance.getModelName());
            view.setRowId(instance.getRowId());
            view.setInstanceStatus(instance.getStatus());
        }
    }
}
