package io.softa.starter.message.dlq.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;

/**
 * Dead Letter Message Model Controller
 */
@Tag(name = "DeadLetterMessage")
@RestController
@RequestMapping("/DeadLetterMessage")
public class DeadLetterMessageController extends EntityController<DeadLetterMessageService, DeadLetterMessage, Long> {

}
