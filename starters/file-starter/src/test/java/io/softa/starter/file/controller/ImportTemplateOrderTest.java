package io.softa.starter.file.controller;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.starter.file.entity.ImportTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The order {@code listByModel} offers templates in. */
class ImportTemplateOrderTest {

    private static ImportTemplate template(Long id, Integer sequence) {
        ImportTemplate template = new ImportTemplate();
        template.setId(id);
        template.setSequence(sequence);
        return template;
    }

    @Test
    void bySequenceThenIdWithUnsetSequenceLast() {
        List<ImportTemplate> templates = List.of(
                template(1L, null), template(2L, 20), template(3L, 10), template(4L, null), template(5L, 10));

        assertThat(ImportTemplateController.inDisplayOrder(templates))
                .extracting(ImportTemplate::getId)
                .containsExactly(3L, 5L, 2L, 1L, 4L);
    }
}
