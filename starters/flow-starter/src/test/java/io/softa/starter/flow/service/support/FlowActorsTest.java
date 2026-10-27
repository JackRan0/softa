package io.softa.starter.flow.service.support;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The current actor is the context's user id; a call without one is refused.
 */
class FlowActorsTest {

    @Test
    void currentUserIdIsTheContextUser() {
        Context ctx = new Context();
        ctx.setUserId(42L);
        ContextHolder.runWith(ctx, () -> assertEquals("42", FlowActors.currentUserId()));
    }

    @Test
    void callWithoutUserIsRefused() {
        ContextHolder.runWith(new Context(),
                () -> assertThrows(FlowAuthorizationException.class, FlowActors::currentUserId));
    }
}
