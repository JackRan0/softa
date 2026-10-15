package io.softa.starter.es.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.jdbc.pipeline.DataPipelineProxy;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Turning a page of change logs into display values without inventing changes.
 *
 * <p>The read pipeline is built for rows that carry every field asked for, and a log carries only
 * what it wrote. Masking writes its field into every row it is handed, present or not; on a log,
 * that reads as a change it never made.
 */
class ChangeLogEnrichmentTest {

    @Test
    @SuppressWarnings("unchecked")
    void aLogKeepsOnlyTheFieldsItWrote() {
        ChangeLogServiceImpl service = new ChangeLogServiceImpl();
        DataPipelineProxy pipeline = mock(DataPipelineProxy.class);
        ReflectionTestUtils.setField(service, "dataPipelineProxy", pipeline);
        // What masking does: every field it was asked about, into every row, whether there or not.
        doAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            List<Map<String, Object>> rows = invocation.getArgument(2);
            rows.forEach(row -> query.getFields().forEach(field -> row.put(field, row.get(field))));
            return null;
        }).when(pipeline).processReadData(eq("EmployeeProfile"), any(FlexQuery.class), any(List.class));

        // Two saves on one page: one filled in a contact field, the other only moved the birthday.
        ChangeLog contact = log(Map.of("personalPhone", "x"), Map.of("personalPhone", "+65 9000"));
        ChangeLog birthday = log(Map.of("dateOfBirth", "1981-05-28"), Map.of("dateOfBirth", "1981-05-29"));
        Page<ChangeLog> page = Page.of(1, 50);
        page.setRows(new ArrayList<>(List.of(contact, birthday)));

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelStoredFields("EmployeeProfile"))
                    .thenReturn(List.of("personalPhone", "dateOfBirth"));
            service.processChangeLogData("EmployeeProfile", page, ConvertType.REFERENCE);
        }

        assertThat(birthday.getDataAfterChange()).containsOnlyKeys("dateOfBirth");
        assertThat(birthday.getDataBeforeChange()).containsOnlyKeys("dateOfBirth");
        assertThat(contact.getDataAfterChange()).containsOnlyKeys("personalPhone");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeletedRowsValuesAreConvertedAsACreatedRowsAre() {
        ChangeLogServiceImpl service = new ChangeLogServiceImpl();
        DataPipelineProxy pipeline = mock(DataPipelineProxy.class);
        ReflectionTestUtils.setField(service, "dataPipelineProxy", pipeline);
        // What the read pipeline does to a file field: the stored id becomes the file it names.
        doAnswer(invocation -> {
            List<Map<String, Object>> rows = invocation.getArgument(2);
            rows.forEach(row -> row.computeIfPresent("fileId", (k, v) -> Map.of("fileId", v, "fileName", "contract.pdf")));
            return null;
        }).when(pipeline).processReadData(eq("ContractFile"), any(FlexQuery.class), any(List.class));

        ChangeLog removed = new ChangeLog();
        removed.setModel("ContractFile");
        removed.setAccessType(AccessType.DELETE);
        removed.setDataBeforeChange(new HashMap<>(Map.of("fileId", 91L)));
        Page<ChangeLog> page = Page.of(1, 50);
        page.setRows(new ArrayList<>(List.of(removed)));

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelStoredFields("ContractFile")).thenReturn(List.of("fileId"));
            service.processChangeLogData("ContractFile", page, ConvertType.REFERENCE);
        }

        assertThat(removed.getDataBeforeChange().get("fileId"))
                .isEqualTo(Map.of("fileId", 91L, "fileName", "contract.pdf"));
    }

    private static ChangeLog log(Map<String, Object> before, Map<String, Object> after) {
        ChangeLog log = new ChangeLog();
        log.setModel("EmployeeProfile");
        log.setAccessType(AccessType.UPDATE);
        log.setDataBeforeChange(new HashMap<>(before));
        log.setDataAfterChange(new HashMap<>(after));
        return log;
    }
}
