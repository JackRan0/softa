package io.softa.starter.flow;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.event.SeedFlowPublisher;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.engine.InProcessFlowClient;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.spi.impl.InboxApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.FlowOrgService;
import io.softa.starter.flow.runtime.spi.impl.OrganizationServiceProperties;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.message.service.MessageService;

/**
 * Auto configuration for the flow starter.
 */
@ComponentScan
@EnableConfigurationProperties(OrganizationServiceProperties.class)
public class FlowAutoConfiguration {

    /**
     * Default dead-letter policy shared by all flow-starter Pulsar consumers.
     * Redelivery is capped at 3 attempts; messages that keep failing after that
     * are routed to the broker's default DLQ topic ({@code <topic>-<subscription>-DLQ}).
     */
    @Bean("flowDeadLetterPolicy")
    @ConditionalOnClass(DeadLetterPolicy.class)
    @ConditionalOnMissingBean(name = "flowDeadLetterPolicy")
    public DeadLetterPolicy flowDeadLetterPolicy() {
        return DeadLetterPolicy.builder()
                .maxRedeliverCount(3)
                .build();
    }

    /**
     * Default organization lookup for dynamic approver resolution — {@link FlowOrgService}, over the
     * HR conventions permission-starter reads. Declared as an auto-configuration {@code @Bean} (not a component-scanned
     * {@code @Component}) so {@link ConditionalOnMissingBean} reliably yields to an
     * application-provided {@link OrganizationService} regardless of bean scan ordering — the
     * previous {@code @Component} + {@code @ConditionalOnMissingBean} combination could silently
     * fail to register, leaving dynamic approver sources resolving to an empty list.
     */
    @Bean
    @ConditionalOnMissingBean(OrganizationService.class)
    public FlowOrgService flowOrgService(ModelService<?> modelService, OrganizationServiceProperties properties) {
        return new FlowOrgService(modelService, properties);
    }

    /**
     * Default in-process {@link FlowClient}. An auto-configuration {@code @Bean} for the same
     * reason as {@link #flowOrgService}: {@link ConditionalOnMissingBean} then
     * reliably yields to an application-provided {@link FlowClient}.
     */
    @Bean
    @ConditionalOnMissingBean(FlowClient.class)
    public InProcessFlowClient flowInProcessFlowClient(
            FlowRuntimeEngine engine, FlowLaunchService launch, FormPermissionService forms,
            FlowApprovalTaskQueryService taskQuery, FlowApprovalRecordQueryService recordQuery,
            FlowInboxService inbox, FlowInstanceService instances,
            FlowNodeDescriptorRegistry descriptors, FlowInstanceAccessGuard accessGuard) {
        return new InProcessFlowClient(engine, launch, forms, taskQuery, recordQuery, inbox,
                instances, descriptors, accessGuard);
    }

    /**
     * Default notification service: inbox messages through message-starter, written after the
     * action commits. Yields to an application-provided {@link ApprovalNotificationService} via
     * {@link ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalNotificationService.class)
    public InboxApprovalNotificationService flowInboxApprovalNotificationService(
            ObjectProvider<MessageService> messageService, ModelService<?> modelService,
            PlatformTransactionManager transactionManager) {
        return new InboxApprovalNotificationService(messageService, modelService, transactionManager);
    }

    /**
     * Publishes the flow designs tenant seed files create, when the application uses seed-starter
     * (an optional dependency: the class is checked by name before anything loads it).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.softa.starter.seed.event.SeedFileLoadedEvent")
    static class SeedFlowPublishing {

        @Bean
        SeedFlowPublisher flowSeedFlowPublisher(FlowPublishService publishService) {
            return new SeedFlowPublisher(publishService);
        }
    }
}

