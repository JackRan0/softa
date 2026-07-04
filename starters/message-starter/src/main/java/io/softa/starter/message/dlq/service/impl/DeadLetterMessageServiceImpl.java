package io.softa.starter.message.dlq.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;


/**
 * Dead Letter Message Model Service Implementation
 */
@Service
public class DeadLetterMessageServiceImpl extends EntityServiceImpl<DeadLetterMessage, Long> implements DeadLetterMessageService {

}
