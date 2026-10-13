package io.softa.framework.base.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Guards the floats-as-BigDecimal policy in {@link JacksonConfig}.
 * JSON and DTO columns store amounts with their scale;
 * read as double, {@code 502.00} came back as {@code 502.0}, the scale lost.
 */
class JacksonBigDecimalForFloatsTest {

    private record Amount(BigDecimal amount, Integer count) {}

    private static final String JSON = "{\"amount\":502.00,\"count\":12}";

    private static final BigDecimal EXACT = new BigDecimal("502.00");

    private static JsonMapper mapperWithSoftaPolicy() {
        return JacksonConfig.applyGlobalPolicy(JsonMapper.builder()).build();
    }

    /** A float reads as a BigDecimal with its scale, whether into a tree, an untyped Map or a typed bean. */
    @Test
    void floats_readAsBigDecimal_withTheirScale() throws Exception {
        JsonNode tree = mapperWithSoftaPolicy().readTree(JSON);
        assertThat(tree.get("amount").isBigDecimal()).isTrue();
        assertThat(tree.get("amount").decimalValue()).isEqualTo(EXACT);

        Map<String, Object> map = mapperWithSoftaPolicy().readValue(JSON, new TypeReference<>() {});
        assertThat(map.get("amount")).isEqualTo(EXACT);

        Amount bean = mapperWithSoftaPolicy().readValue(JSON, Amount.class);
        assertThat(bean.amount()).isEqualTo(EXACT);
    }

    /** Integral numbers are left alone: they still read as integers, not as BigDecimal. */
    @Test
    void integralNumbers_stayIntegral() throws Exception {
        JsonNode tree = mapperWithSoftaPolicy().readTree(JSON);
        assertThat(tree.get("count").isIntegralNumber()).isTrue();

        Map<String, Object> map = mapperWithSoftaPolicy().readValue(JSON, new TypeReference<>() {});
        assertThat(map.get("count")).isEqualTo(12);
    }

    /** A JSON column is read as a tree first and becomes a DTO afterwards; the scale survives both hops. */
    @Test
    void treeToBean_keepsTheScale() throws Exception {
        JsonNode tree = mapperWithSoftaPolicy().readTree(JSON);
        Amount bean = mapperWithSoftaPolicy().treeToValue(tree, Amount.class);
        assertThat(bean.amount()).isEqualTo(EXACT);
    }

    /** Without an application context JsonUtils builds its own mapper, and that mapper reads floats the same way. */
    @Test
    void fallbackMapper_outsideSpring_followsThePolicy() {
        JsonNode tree = JsonUtils.stringToObject(JSON, JsonNode.class);
        assertThat(tree.get("amount").decimalValue()).isEqualTo(EXACT);
    }

    /** Control case: a mapper without the policy reads the float as a double, and the scale is lost. */
    @Test
    void withoutThePolicy_floatsLoseTheirScale() throws Exception {
        JsonNode tree = JsonMapper.builder().build().readTree(JSON);
        assertThat(tree.get("amount").isBigDecimal()).isFalse();
        assertThat(tree.get("amount").decimalValue()).isEqualTo(new BigDecimal("502.0"));
    }
}
