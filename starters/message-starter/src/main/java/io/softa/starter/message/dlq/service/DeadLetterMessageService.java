package io.softa.starter.message.dlq.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.dlq.entity.DeadLetterMessage;

/**
 * Dead Letter Message Model Service Interface
 */
public interface DeadLetterMessageService extends EntityService<DeadLetterMessage, Long> {

}
