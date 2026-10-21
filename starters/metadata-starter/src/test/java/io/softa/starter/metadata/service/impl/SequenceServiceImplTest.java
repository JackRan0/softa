package io.softa.starter.metadata.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.softa.starter.metadata.sequence.SequenceTemplatePreview;

import io.softa.starter.metadata.sequence.CompanyAbbrPreview;

import io.softa.framework.orm.domain.FlexQuery;

import java.util.Optional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.sequence.exception.SequenceException;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.enums.ResetCadence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.sequence.CompanyAbbreviationResolver;
import io.softa.starter.metadata.sequence.SequenceCodeOccupancy;
import io.softa.starter.metadata.sequence.TemplateRenderer;
import io.softa.starter.metadata.sequence.dialect.SequenceDialect;
import io.softa.starter.metadata.sequence.dialect.SequenceDialectFactory;
import io.softa.starter.metadata.service.SysSequenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rendering per record and skipping values already in use. The counter is simulated: each allocation
 * of n numbers advances it by n, as the dialect's UPDATE does.
 */
class SequenceServiceImplTest {

    private static final String CODE = "Employee.code";

    private final JdbcProxy jdbcProxy = mock(JdbcProxy.class);
    private final SysSequenceService sysSequenceService = mock(SysSequenceService.class);
    private final SequenceDialectFactory dialectFactory = mock(SequenceDialectFactory.class);
    private final SequenceDialect dialect = mock(SequenceDialect.class);
    private final CompanyAbbreviationResolver abbreviations = mock(CompanyAbbreviationResolver.class);
    private final SequenceCodeOccupancy occupancy = mock(SequenceCodeOccupancy.class);
    private final AtomicLong counter = new AtomicLong();
    private final Set<String> takenInTable = new HashSet<>();
    private SequenceServiceImpl service;
    private SystemConfig originalConfig;

    @AfterEach
    void restoreConfig() {
        SystemConfig.env = originalConfig;
    }

    @BeforeEach
    void setUp() {
        // A fresh config of our own: other tests switch multi-tenancy on in this shared static, and
        // whether they ran first must not decide whether an allocation here needs a tenant.
        originalConfig = SystemConfig.env;
        SystemConfig.env = new SystemConfig();
        service = new SequenceServiceImpl(jdbcProxy, sysSequenceService, new TemplateRenderer(), dialectFactory,
                abbreviations, occupancy);
        ReflectionTestUtils.setField(service, "self", service);
        when(dialectFactory.getCurrentDialect()).thenReturn(dialect);
        when(dialect.buildAllocateSql(any(), anyLong(), anyLong(), anyInt(), any(), any())).thenAnswer(inv -> {
            counter.addAndGet((int) inv.getArgument(3));
            return mock(SqlParams.class);
        });
        when(jdbcProxy.update(eq("SysSequence"), any(SqlParams.class))).thenReturn(1);
        when(dialect.fetchEndValue(eq("SysSequence"), any(), any())).thenAnswer(inv -> counter.get());
        when(occupancy.taken(eq(CODE), anyCollection())).thenAnswer(inv -> {
            Set<String> hit = new HashSet<>();
            for (String c : inv.<Collection<String>>getArgument(1)) {
                if (takenInTable.contains(SequenceCodeOccupancy.normalize(c))) {
                    hit.add(SequenceCodeOccupancy.normalize(c));
                }
            }
            return hit;
        });
    }

    private void config(String template, long current) {
        SysSequence cfg = new SysSequence();
        cfg.setId(1L);
        cfg.setCode(CODE);
        cfg.setTemplate(template);
        cfg.setStartValue(1L);
        cfg.setIncrementStep(1);
        cfg.setResetCadence(ResetCadence.NONE);
        cfg.setMode(SequenceMode.NO_GAP);
        when(sysSequenceService.loadConfigByCode(CODE)).thenReturn(cfg);
        counter.set(current);
    }

    private static List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Map.of());
        }
        return out;
    }

    private List<String> batch(List<Map<String, Object>> rows, Collection<String> claimed) {
        return ContextHolder.callWith(new Context(), () -> service.nextBatch(CODE, rows, claimed));
    }

    @Test
    @DisplayName("each record gets its own company's prefix off one shared counter")
    void rendersEachRowWithItsOwnAbbreviation() {
        config("{companyAbbr}{seq:7}", 1000020);
        List<Map<String, Object>> rows = rows(3);
        when(abbreviations.abbreviationsOf("Employee", rows)).thenReturn(java.util.Arrays.asList("ZK", "HF", null));
        assertThat(batch(rows, Set.of())).containsExactly("ZK1000021", "HF1000022", "1000023");
    }

    @Test
    @DisplayName("a template without the token looks no company up")
    void noTokenNoLookup() {
        config("E{seq:4}", 0);
        assertThat(batch(rows(2), Set.of())).containsExactly("E0001", "E0002");
        verify(abbreviations, never()).abbreviationsOf(anyString(), anyList());
    }

    @Test
    @DisplayName("a value already in the table is skipped; the counter moves on")
    void skipsValueInTable() {
        config("E{seq:4}", 29);
        takenInTable.add("e0030");
        assertThat(batch(rows(1), Set.of())).containsExactly("E0031");
    }

    @Test
    @DisplayName("a value typed into another record of the same write is skipped, ignoring case")
    void skipsValueClaimedInSameBatch() {
        config("E{seq:4}", 29);
        assertThat(batch(rows(1), List.of("e0030"))).containsExactly("E0031");
    }

    @Test
    @DisplayName("only the clashing record is renumbered; the others keep their values")
    void othersKeepTheirValues() {
        config("E{seq:4}", 29);
        takenInTable.add("e0030");
        assertThat(batch(rows(2), Set.of())).containsExactly("E0032", "E0031");
    }

    @Test
    @DisplayName("a prefixed code taken for one company is skipped for that record only, and the number is spent")
    void prefixedClashSpendsTheSharedNumber() {
        config("{companyAbbr}{seq:4}", 29);
        List<Map<String, Object>> rows = rows(2);
        when(abbreviations.abbreviationsOf("Employee", rows)).thenReturn(List.of("ZK", "HF"));
        takenInTable.add("zk0030");
        assertThat(batch(rows, Set.of())).containsExactly("ZK0032", "HF0031");
    }

    @Test
    @DisplayName("gives up rather than loop when every value it renders is taken")
    void givesUp() {
        config("E", 0);   // no {seq}: every allocation renders the same value
        takenInTable.add("e");
        assertThatThrownBy(() -> batch(rows(1), Set.of())).isInstanceOf(SequenceException.class);
    }

    @Test
    @DisplayName("next(code) still works and is checked like any other allocation")
    void plainNextIsChecked() {
        config("E{seq:4}", 29);
        takenInTable.add("e0030");
        assertThat(ContextHolder.callWith(new Context(), () -> service.next(CODE))).isEqualTo("E0031");
    }

    // ---- template preview ---------------------------------------------------

    private void stored(Long current, String lastResetKey, ResetCadence cadence) {
        SysSequence row = new SysSequence();
        row.setCode(CODE);
        row.setCurrentValue(current);
        row.setLastResetKey(lastResetKey);
        row.setResetCadence(cadence);
        when(sysSequenceService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(row));
        when(abbreviations.previewFor("Employee")).thenReturn(new CompanyAbbrPreview(true, "ZingKey Pte Ltd", "ZK"));
    }

    @Test
    @DisplayName("preview: a used counter in the current period continues")
    void previewContinues() {
        stored(1_000_009L, "", ResetCadence.NONE);
        SequenceTemplatePreview p = service.templatePreview(CODE);
        assertEquals(new SequenceTemplatePreview(true, 1_000_009L,
                new CompanyAbbrPreview(true, "ZingKey Pte Ltd", "ZK")), p);
    }

    @Test
    @DisplayName("preview: a counter never used (null last reset key) starts over, as allocation does")
    void previewFreshCounterStartsOver() {
        stored(0L, null, ResetCadence.NONE);
        assertFalse(service.templatePreview(CODE).continuesCounter());
    }

    @Test
    @DisplayName("preview: a counter from an earlier reset period starts over")
    void previewNewPeriodStartsOver() {
        stored(42L, "1999", ResetCadence.YEARLY);
        SequenceTemplatePreview p = service.templatePreview(CODE);
        assertFalse(p.continuesCounter());
        assertEquals(42L, p.currentValue());
    }
}
