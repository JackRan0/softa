package io.softa.starter.flow.runtime.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

/**
 * The application's {@link ModelApprovalHandler}s, by the model they serve. Two handlers for one
 * model fail the boot: which one a submission reached would depend on bean order.
 *
 * <p>Handlers are looked up once every singleton exists rather than injected: a business service
 * that is a handler and also submits its records through {@code FlowClient} would otherwise close a
 * construction cycle through this registry.
 */
@Component
public class ModelApprovalHandlerRegistry implements SmartInitializingSingleton {

    private final Supplier<List<ModelApprovalHandler>> handlers;
    private volatile Map<String, ModelApprovalHandler> handlersByModel;

    @Autowired
    public ModelApprovalHandlerRegistry(ObjectProvider<ModelApprovalHandler> handlers) {
        this.handlers = () -> handlers.orderedStream().toList();
    }

    /** A registry over a fixed set of handlers. */
    public static ModelApprovalHandlerRegistry of(List<ModelApprovalHandler> handlers) {
        return new ModelApprovalHandlerRegistry(List.copyOf(handlers));
    }

    private ModelApprovalHandlerRegistry(List<ModelApprovalHandler> handlers) {
        this.handlers = () -> handlers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        handlersByModel();
    }

    /** The handler of {@code modelName}, if the application has one. */
    public Optional<ModelApprovalHandler> find(String modelName) {
        return modelName == null ? Optional.empty() : Optional.ofNullable(handlersByModel().get(modelName));
    }

    /**
     * The handler of {@code modelName}.
     *
     * @throws BusinessException when the application has none: its records cannot be submitted
     */
    public ModelApprovalHandler require(String modelName) {
        return find(modelName).orElseThrow(() -> new BusinessException(
                "Records of {0} cannot be submitted for approval: the application has no approval handler for them.",
                modelName));
    }

    private Map<String, ModelApprovalHandler> handlersByModel() {
        Map<String, ModelApprovalHandler> index = handlersByModel;
        if (index == null) {
            synchronized (this) {
                if (handlersByModel == null) {
                    handlersByModel = index(handlers.get());
                }
                index = handlersByModel;
            }
        }
        return index;
    }

    private static Map<String, ModelApprovalHandler> index(List<ModelApprovalHandler> handlers) {
        Map<String, ModelApprovalHandler> byModel = new HashMap<>();
        for (ModelApprovalHandler handler : handlers) {
            String modelName = handler.modelName();
            if (!StringUtils.hasText(modelName)) {
                throw new IllegalStateException("Approval handler " + handler.getClass().getName()
                        + " does not name the model it serves");
            }
            ModelApprovalHandler previous = byModel.putIfAbsent(modelName, handler);
            if (previous != null) {
                throw new IllegalStateException("Model " + modelName + " has two approval handlers: "
                        + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        return Map.copyOf(byModel);
    }
}
