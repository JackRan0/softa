package io.softa.framework.web.config;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conversion the web layer runs for an enum request parameter accepts the value JSON uses for it: the
 * {@code @JsonValue} when the enum declares one, the constant name otherwise, and no other spelling. Spring MVC's
 * own constant-name fallback, which runs only after this conversion fails, is outside this test.
 */
class EnumRequestParamBindingTest {

    /** Converts the way the web layer binds a query, path or header parameter. */
    private final DefaultFormattingConversionService conversionService = webConversionService();

    /** The JSON value binds, including one that differs from the constant name by more than case. */
    @Test
    void jsonValueBinds() {
        assertThat(conversionService.convert("Process", Scenario.class)).isEqualTo(Scenario.PROCESS);
        assertThat(conversionService.convert("ReadOnlyView", Scenario.class)).isEqualTo(Scenario.READ_ONLY_VIEW);
    }

    /** The constant name of an enum with {@code @JsonValue} is refused, and so is the value in another case. */
    @Test
    void otherSpellingsAreRefused() {
        assertThatThrownBy(() -> conversionService.convert("PROCESS", Scenario.class))
                .isInstanceOf(ConversionFailedException.class)
                .rootCause()
                .hasMessage("'PROCESS' is not a Scenario value; accepted: Process, ReadOnlyView");
        assertThatThrownBy(() -> conversionService.convert("process", Scenario.class))
                .isInstanceOf(ConversionFailedException.class);
    }

    /** An enum without {@code @JsonValue} binds by its constant name, as before. */
    @Test
    void plainEnumBindsByConstantName() {
        assertThat(conversionService.convert("ACTIVE", Status.class)).isEqualTo(Status.ACTIVE);
    }

    /** Surrounding blanks are trimmed and a blank value binds as null, as with Spring's own enum binding. */
    @Test
    void blanksAreTrimmed() {
        assertThat(conversionService.convert(" Process ", Scenario.class)).isEqualTo(Scenario.PROCESS);
        assertThat(conversionService.convert("  ", Scenario.class)).isNull();
    }

    /** A constant with a body has a class of its own; asking for that class still binds within its enum. */
    @Test
    void constantWithBodyBinds() {
        assertThat(conversionService.convert("Up", Rounding.UP.getClass())).isEqualTo(Rounding.UP);
    }

    /** A conversion service carrying the formatters {@link BaseWebConfig} registers for Spring MVC. */
    private static DefaultFormattingConversionService webConversionService() {
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        new BaseWebConfig(null).addFormatters(service);
        return service;
    }

    enum Scenario {
        PROCESS("Process"),
        READ_ONLY_VIEW("ReadOnlyView");

        @JsonValue
        private final String type;

        Scenario(String type) {
            this.type = type;
        }
    }

    enum Status {
        ACTIVE, INACTIVE
    }

    enum Rounding {
        UP("Up") {
            @Override
            long apply(double value) {
                return (long) Math.ceil(value);
            }
        },
        DOWN("Down") {
            @Override
            long apply(double value) {
                return (long) Math.floor(value);
            }
        };

        @JsonValue
        private final String code;

        Rounding(String code) {
            this.code = code;
        }

        abstract long apply(double value);
    }
}
