package io.softa.starter.flow.runtime.spi.impl;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;

/**
 * Tells every channel — the inbox always, email on the flows that send it, another channel an application adds
 * — each on its own: one channel failing does not keep the others quiet, nor touch the action.
 */
@Slf4j
public class CompositeApprovalNotificationService implements ApprovalNotificationService {

    private final List<ApprovalNotificationService> channels;

    public CompositeApprovalNotificationService(List<ApprovalNotificationService> channels) {
        this.channels = List.copyOf(channels);
    }

    @Override
    public void notify(FlowNotificationEvent event) {
        for (ApprovalNotificationService channel : channels) {
            try {
                channel.notify(event);
            } catch (RuntimeException e) {
                log.warn("{} could not send a flow notification: {}", channel.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }
}
