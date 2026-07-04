package io.softa.starter.metadata.ddl.spi;

/**
 * Optional override of the built-in {@code .peb} DDL templates per database.
 *
 * <p>Returned by {@link DdlMetadataResolver#getDdlTemplates}. Any field may be
 * {@code null} or blank to fall back to the corresponding built-in template
 * resource (e.g. {@code classpath:templates/sql/<db>/CreateTable.peb}).
 *
 * @param createTableTemplate override for {@code CreateTable.peb}
 * @param alterTableTemplate  override for {@code AlterTable.peb}
 * @param dropTableTemplate   override for {@code DropTable.peb}
 * @param alterIndexTemplate  override for {@code AlterIndex.peb}
 */
public record DdlTemplateBundle(
        String createTableTemplate,
        String alterTableTemplate,
        String dropTableTemplate,
        String alterIndexTemplate
) {}
