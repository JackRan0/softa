package io.softa.starter.metadata.sequence;

import java.util.Map;

/**
 * A form asking whether a code typed by hand would be accepted, before saving.
 *
 * @param code     the sequence code, {@code "<ModelName>.<fieldName>"}
 * @param value    the typed code
 * @param row      the form's current values — read for the record's company (its legal entity,
 *                 company or employee), so Same as Auto-generation follows what the form says now
 * @param recordId the record being edited, null on create
 */
public record ManualCodeCheckRequest(String code, String value, Map<String, Object> row, String recordId) {
}
