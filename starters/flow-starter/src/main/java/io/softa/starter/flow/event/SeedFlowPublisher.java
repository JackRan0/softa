package io.softa.starter.flow.event;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.seed.event.SeedFileLoadedEvent;

/**
 * Publishes the flow designs a tenant seed file creates. A default flow ships as a seeded
 * {@code FlowDesign} row and is usable — its submit trigger matched, its instances started — only
 * once compiled and published, so each tenant's copy is published as it arrives. The tenant then
 * edits and republishes its own copy in the designer; seed files run once per tenant and never
 * bring the template back over it.
 *
 * <p>Runs inside the seed file's transaction, in the tenant's context: a design that does not
 * compile throws, the file rolls back and is recorded as failed in that tenant, and runs again
 * once the template is fixed.
 */
@Slf4j
public class SeedFlowPublisher {

    private static final String FLOW_DESIGN_MODEL = FlowDesign.class.getSimpleName();
    private static final String CHANGE_DESCRIPTION = "Default flow";

    private final FlowPublishService publishService;

    public SeedFlowPublisher(FlowPublishService publishService) {
        this.publishService = publishService;
    }

    @EventListener
    public void onSeedFileLoaded(SeedFileLoadedEvent event) {
        List<String> flowIds = event.createdRows().getOrDefault(FLOW_DESIGN_MODEL, List.of());
        for (String flowId : flowIds) {
            publishService.publishDraft(flowId, CHANGE_DESCRIPTION);
            log.info("Published seeded flow {} in tenant {} (seed file {})", flowId, event.tenantId(), event.fileName());
        }
    }
}
