package io.softa.starter.metadata.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which generated codes are already in the field: exact, case-insensitive, seeing every row. */
class SequenceCodeOccupancyTest {

    private static final String CODE = "Employee.code";

    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final SequenceCodeOccupancy occupancy = new SequenceCodeOccupancy(modelService);
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull("Employee", "code")).thenReturn(mock(MetaField.class));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private void tableHas(String... codes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String c : codes) {
            rows.add(Map.of("code", c));
        }
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenReturn(rows);
    }

    private Set<String> taken(List<String> candidates) {
        Context ctx = new Context();
        ctx.setGrantedCountries(Set.of("SG"));
        ctx.setCompanyCountry("SG");
        return ContextHolder.callWith(ctx, () -> occupancy.taken(CODE, candidates));
    }

    @Test
    @DisplayName("a code differing only in case is taken")
    void caseInsensitive() {
        tableHas("zk1000030");
        assertThat(taken(List.of("ZK1000030", "ZK1000031"))).containsExactly("zk1000030");
    }

    @Test
    @DisplayName("a longer code sharing the prefix is not taken — the prefix fetch over-fetches, the comparison is exact")
    void prefixOverFetchIsFilteredOut() {
        tableHas("ZK10000300", "PGX00001");
        assertThat(taken(List.of("ZK1000030", "PG_00001"))).isEmpty();
    }

    @Test
    @DisplayName("the check waives permissions and the country narrowing, which would hide taken codes")
    void seesEveryRow() {
        AtomicReference<Context> seen = new AtomicReference<>();
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenAnswer(inv -> {
            seen.set(ContextHolder.getContext());
            return List.of();
        });
        taken(List.of("ZK1000030"));
        assertThat(seen.get().isSkipPermissionCheck()).isTrue();
        assertThat(seen.get().getGrantedCountries()).isNull();
        assertThat(seen.get().getCompanyCountry()).isNull();
    }

    @Test
    @DisplayName("large batches are checked in bounded chunks")
    void chunked() {
        tableHas();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            many.add("E" + i);
        }
        taken(many);
        verify(modelService, times(3)).searchList(eq("Employee"), any(FlexQuery.class));
    }

    @Test
    @DisplayName("a code that names no field of a known model is not checked")
    void unknownField() {
        assertThat(occupancy.taken("Nope.code", List.of("X1"))).isEmpty();
        assertThat(occupancy.taken("nodot", List.of("X1"))).isEmpty();
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }
}
