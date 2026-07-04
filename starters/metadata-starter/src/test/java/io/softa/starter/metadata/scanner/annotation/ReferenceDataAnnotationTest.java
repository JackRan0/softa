package io.softa.starter.metadata.scanner.annotation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.entity.CountrySubdivision;
import io.softa.starter.referencedata.entity.Currency;
import io.softa.starter.referencedata.enums.Continent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parse {@code reference-data-starter}'s 3 entities + 1 enum via
 * {@link AnnotationParser} and verify the result.
 *
 * <p>The 3 masters are <b>code-as-id</b>: {@code idStrategy = EXTERNAL_ID} with a
 * {@code String id} that IS the primary ISO code (Currency=alpha-3, CountryRegion=alpha-2,
 * Subdivision=ISO 3166-2). References to them store that code in a plain id-FK. The local
 * {@code Continent} {@code @OptionSet} stays a flat enum (7 items).
 */
class ReferenceDataAnnotationTest {

    private static final List<Class<?>> MODELS = List.of(
            CountryRegion.class,
            Currency.class,
            CountrySubdivision.class
    );

    private static final List<Class<?>> OPTION_SETS = List.of(Continent.class);

    private final AnnotationParser parser = new AnnotationParser();

    @Test
    void parsesAllReferenceDataModels() {
        AnnotationScanResult result = parser.parse(MODELS, OPTION_SETS);

        assertEquals(3, result.models().size());
        Set<String> names = result.models().stream()
                .map(SysModel::getModelName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("CountryRegion", "Currency", "CountrySubdivision"),
                names);

        for (SysModel m : result.models()) {
            assertEquals(IdStrategy.EXTERNAL_ID, m.getIdStrategy(),
                    m.getModelName() + " is code-as-id: the ISO code IS the externally-supplied PK");
        }
    }

    @Test
    void countryRegion_modelAttributes() {
        AnnotationScanResult result = parser.parse(List.of(CountryRegion.class), OPTION_SETS);
        SysModel m = result.models().get(0);
        assertEquals("CountryRegion", m.getModelName());
        assertEquals("country_region", m.getTableName());
        assertEquals(Boolean.FALSE, m.getMultiTenant());
        // code-as-id: the id IS the business key.
        assertEquals(List.of("id"), m.getBusinessKey());
    }

    @Test
    void countryRegion_continentField_inferredAsOptionWithLocalEnumCode() {
        AnnotationScanResult result = parser.parse(List.of(CountryRegion.class), OPTION_SETS);
        SysField continentField = byFieldName(result.fields(), "continent");
        assertEquals(FieldType.OPTION, continentField.getFieldType());
        assertEquals("Continent", continentField.getOptionSetCode(),
                "optionSetCode is derived from Class.getSimpleName()");
    }

    @Test
    void currency_decimalPlacesField_inferredAsInteger() {
        AnnotationScanResult result = parser.parse(List.of(Currency.class), OPTION_SETS);
        SysField decimalPlaces = byFieldName(result.fields(), "decimalPlaces");
        assertEquals(FieldType.INTEGER, decimalPlaces.getFieldType());
        assertEquals(Boolean.TRUE, decimalPlaces.getRequired());
    }

    @Test
    void countrySubdivision_parentCode_isOptionalSelfReferenceById() {
        AnnotationScanResult result = parser.parse(List.of(CountrySubdivision.class), OPTION_SETS);
        SysField parentCode = byFieldName(result.fields(), "parentCode");
        // No required=true → defaults to false
        assertEquals(Boolean.FALSE, parentCode.getRequired());
        // Self-relation joining country_subdivision.id (code-as-id). The parser leaves
        // relatedField unset for a default id-FK; ModelManager.init defaults it to "id". The FK carries
        // no length of its own — the DDL layer mirrors the referenced id column's width.
        assertEquals(FieldType.MANY_TO_ONE, parentCode.getFieldType());
        assertEquals("CountrySubdivision", parentCode.getRelatedModel());
        assertNull(parentCode.getRelatedField());
        assertNull(parentCode.getLength());
    }

    @Test
    void countrySubdivision_countryCode_isFkToCountryRegionById() {
        AnnotationScanResult result = parser.parse(List.of(CountrySubdivision.class), OPTION_SETS);
        SysField countryCode = byFieldName(result.fields(), "countryCode");
        assertEquals(FieldType.MANY_TO_ONE, countryCode.getFieldType());
        assertEquals("CountryRegion", countryCode.getRelatedModel());
        assertNull(countryCode.getRelatedField(), "default id-FK: relatedField unset at parse time");
        assertEquals(Boolean.TRUE, countryCode.getRequired());
    }

    @Test
    void continentEnum_parsesIntoOptionSetWithSevenItems() {
        AnnotationScanResult result = parser.parse(List.of(), OPTION_SETS);

        assertEquals(1, result.optionSets().size());
        SysOptionSet os = result.optionSets().get(0);
        assertEquals("Continent", os.getOptionSetCode());
        assertEquals("Continent", os.getLabel());

        assertEquals(7, result.optionItems().size());
        Set<String> itemCodes = result.optionItems().stream()
                .map(SysOptionItem::getItemCode)
                .collect(Collectors.toSet());
        assertEquals(Set.of("AS", "EU", "AF", "NA", "SA", "OC", "AN"), itemCodes,
                "itemCode comes from @JsonValue (the 'code' field), not Enum.name()");

        for (SysOptionItem item : result.optionItems()) {
            assertEquals("Continent", item.getOptionSetCode());
        }
    }

    @Test
    void continentEnum_labelAndSequenceFromOptionItemAnnotation() {
        AnnotationScanResult result = parser.parse(List.of(), OPTION_SETS);
        SysOptionItem asia = result.optionItems().stream()
                .filter(i -> "AS".equals(i.getItemCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("Asia", asia.getLabel());
        assertEquals(1, asia.getSequence());
    }

    @Test
    void countryRegion_idField_isParsed_asStringPrimaryKey() {
        // Code-as-id: the PK is a String (= the ISO 3166-1 alpha-2 code), inferred from
        // the declared `String id` field, length=2.
        AnnotationScanResult result = parser.parse(List.of(CountryRegion.class), OPTION_SETS);
        SysField id = byFieldName(result.fields(), "id");
        assertEquals(FieldType.STRING, id.getFieldType());
        assertEquals(2, id.getLength());
        assertTrue(id.getRequired());
    }

    @Test
    void fieldCount_matchesAnnotatedFieldsExactly() {
        AnnotationScanResult result = parser.parse(MODELS, OPTION_SETS);
        // Code-as-id dropped the separate `code` field (the id IS the code). Counts include the
        // always-emitted PK id + the 6 audit fields (created*/updated*) from AuditableModel:
        //   CountryRegion:      6 business + id + 6 audit = 13 (name/alpha3Code/dialCode/currencyCode/continent/eea/hasSubdivisions = 7)
        // (re-derived below from the real field set rather than a hand count)
        long crCount = result.fields().stream()
                .filter(f -> "CountryRegion".equals(f.getModelName())).count();
        long curCount = result.fields().stream()
                .filter(f -> "Currency".equals(f.getModelName())).count();
        long csCount = result.fields().stream()
                .filter(f -> "CountrySubdivision".equals(f.getModelName())).count();
        // CountryRegion: id + name,alpha3Code,dialCode,currencyCode,continent,eea,hasSubdivisions (7) + 6 audit = 14
        assertEquals(14, crCount, "CountryRegion: id + 7 business + 6 audit");
        // Currency: id + numericCode,name,symbol,decimalPlaces (4) + 6 audit = 11
        assertEquals(11, curCount, "Currency: id + 4 business + 6 audit");
        // CountrySubdivision: id + countryCode,name,parentCode,type (4) + 6 audit = 11
        assertEquals(11, csCount, "CountrySubdivision: id + 4 business + 6 audit");
    }

    @Test
    void parserPicksUpFieldLengthAndDescription() {
        AnnotationScanResult result = parser.parse(List.of(CountryRegion.class), OPTION_SETS);
        // The id IS the alpha-2 code (code-as-id) — carries the explicit length=2 + description.
        SysField id = byFieldName(result.fields(), "id");
        assertEquals(2, id.getLength(), "ISO 3166-1 alpha-2 → length=2");
        assertEquals("ID", id.getLabel());
        assertNotNull(id.getDescription());
        assertTrue(id.getDescription().contains("ISO 3166-1"));
    }

    @Test
    void staticFieldsFiltered_butIdPrimaryKeyEmitted() {
        // serialVersionUID (static) is never a field. Currency.id is the PK, inferred as STRING
        // from `String id` (code-as-id).
        AnnotationScanResult result = parser.parse(List.of(Currency.class), OPTION_SETS);
        boolean hasStatic = result.fields().stream()
                .anyMatch(f -> "serialVersionUID".equals(f.getFieldName()));
        assertTrue(!hasStatic, "static fields are filtered out");
        SysField id = byFieldName(result.fields(), "id");
        assertEquals(FieldType.STRING, id.getFieldType());
    }

    private static SysField byFieldName(List<SysField> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }
}
