package io.softa.starter.flow.controller;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.softa.framework.web.config.BaseWebConfig;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The editor asks for its palette with {@code ?scenario=Approval}, the value a flow design carries in JSON; it reaches
 * the registry as {@link FlowScenario#APPROVAL}.
 */
class FlowNodeDescriptorControllerScenarioParamTest {

    private final FlowNodeDescriptorRegistry registry = mock(FlowNodeDescriptorRegistry.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(registry.listByScenario(any())).thenReturn(List.of());
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        new BaseWebConfig(null).addFormatters(conversionService);
        mockMvc = MockMvcBuilders.standaloneSetup(new FlowNodeDescriptorController(registry))
                .setConversionService(conversionService)
                .build();
    }

    /** Each scenario's JSON value selects that scenario. */
    @Test
    void jsonValueSelectsTheScenario() throws Exception {
        for (FlowScenario scenario : FlowScenario.values()) {
            mockMvc.perform(get("/flow/nodeDescriptors").param("scenario", scenario.getType()))
                    .andExpect(status().isOk());
            verify(registry).listByScenario(scenario);
        }
    }

    /** A value that names no scenario — the retired {@code Process} among them — is a bad request and never reaches the registry. */
    @Test
    void unknownValueIsRefused() throws Exception {
        mockMvc.perform(get("/flow/nodeDescriptors").param("scenario", "Process"))
                .andExpect(status().isBadRequest());
        verify(registry, never()).listByScenario(any());
    }
}
