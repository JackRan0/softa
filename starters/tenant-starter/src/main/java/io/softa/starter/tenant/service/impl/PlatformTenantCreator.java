package io.softa.starter.tenant.service.impl;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.tenant.config.PlatformTenantProperties;
import io.softa.starter.tenant.enums.TenantStatus;

import static io.softa.framework.base.constant.BaseConstant.PLATFORM_TENANT_ID;
import static io.softa.framework.orm.constant.ModelConstant.CREATED_BY;
import static io.softa.framework.orm.constant.ModelConstant.CREATED_ID;
import static io.softa.framework.orm.constant.ModelConstant.CREATED_TIME;
import static io.softa.framework.orm.constant.ModelConstant.ID;
import static io.softa.framework.orm.constant.ModelConstant.SOFT_DELETED_FIELD;
import static io.softa.framework.orm.constant.ModelConstant.UPDATED_BY;
import static io.softa.framework.orm.constant.ModelConstant.UPDATED_ID;
import static io.softa.framework.orm.constant.ModelConstant.UPDATED_TIME;

/**
 * Writes the platform tenant row.
 *
 * <p>Through JDBC rather than the model layer, because the row's id is fixed and the model layer would not
 * keep it: {@code IdProcessor} gives every insert a generated id unless the global {@code enableInsertId}
 * switch is on, and turning that on to plant one row would change the id of every insert the application
 * makes. Only the id forces this; nothing else about the row is unusual.
 *
 * <p>What bypassing the model layer costs is that nothing checks this column list against the model.
 * {@link #INSERTED_FIELDS} is that list, and a test holds it to the entity — every stored required field
 * without a default must be in it — so adding one to {@code TenantInfo} breaks the build here instead of the
 * next fresh deployment. Table and column names are read from the metadata, so a rename needs nothing.
 */
@Slf4j
@Component
public class PlatformTenantCreator {

    static final String MODEL = "TenantInfo";

    /** Every field this insert writes, in column order. Held to {@code TenantInfo} by a test. */
    static final List<String> INSERTED_FIELDS = List.of(
            ID, "name", "code", "status", "activatedTime",
            "defaultLanguage", "defaultTimezone", "defaultCountry", SOFT_DELETED_FIELD,
            CREATED_TIME, CREATED_ID, CREATED_BY, UPDATED_TIME, UPDATED_ID, UPDATED_BY);

    /** Who the audit columns name. No user exists yet when this runs; the marker matches other system writes. */
    private static final long SYSTEM_USER_ID = 0L;
    private static final String SYSTEM_USER = "system";

    private final JdbcProxy jdbcProxy;
    private final PlatformTenantProperties properties;

    public PlatformTenantCreator(JdbcProxy jdbcProxy, PlatformTenantProperties properties) {
        this.jdbcProxy = jdbcProxy;
        this.properties = properties;
    }

    /**
     * Create the platform tenant unless a row with its id is already there.
     *
     * @return true when this call created it
     */
    public boolean createIfAbsent() {
        String table = ModelManager.getModel(MODEL).getTableName();
        // Physical check, without the soft-delete predicate the model layer would add: a soft-deleted row
        // still holds the primary key, and treating it as absent would end in a key violation here.
        SqlParams exists = new SqlParams("SELECT COUNT(*) FROM " + table + " WHERE " + column(ID) + " = ?");
        exists.addArgValue(PLATFORM_TENANT_ID);
        Number count = (Number) jdbcProxy.queryForObject(MODEL, exists, Long.class);
        if (count != null && count.longValue() > 0) {
            return false;
        }

        Map<String, Object> row = row();
        SqlParams insert = new SqlParams("INSERT INTO " + table
                + " (" + row.keySet().stream().map(PlatformTenantCreator::column).collect(Collectors.joining(", "))
                + ") VALUES (" + row.keySet().stream().map(f -> "?").collect(Collectors.joining(", ")) + ")");
        row.values().forEach(insert::addArgValue);
        try {
            jdbcProxy.update(MODEL, insert);
        } catch (DuplicateKeyException e) {
            // Another instance got there between the check and this insert — what happens when several
            // replicas start together against a fresh database. The row exists, which is all this wanted.
            log.info("The platform tenant was created concurrently by another instance.");
            return false;
        }
        log.info("Created the platform tenant (id {}).", PLATFORM_TENANT_ID);
        return true;
    }

    /** The row to insert, keyed by field name in {@link #INSERTED_FIELDS} order. */
    Map<String, Object> row() {
        String country = require(properties.getDefaultCountry(), "softa.tenant.platform.default-country");
        String timezoneCode = require(properties.getDefaultTimezone(), "softa.tenant.platform.default-timezone");
        Timezone timezone = Timezone.of(timezoneCode);
        if (timezone == null) {
            throw new IllegalStateException("softa.tenant.platform.default-timezone '" + timezoneCode
                    + "' is not a known timezone code (expected the form UTC+08:00).");
        }
        Language language = null;
        if (StringUtils.isNotBlank(properties.getDefaultLanguage())) {
            language = Language.of(properties.getDefaultLanguage());
            if (language == null) {
                throw new IllegalStateException("softa.tenant.platform.default-language '"
                        + properties.getDefaultLanguage() + "' is not a known language code (e.g. en-US).");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ID, PLATFORM_TENANT_ID);
        row.put("name", properties.getName());
        row.put("code", properties.getCode());
        row.put("status", TenantStatus.ACTIVE.getStatus());
        row.put("activatedTime", now);
        row.put("defaultLanguage", language == null ? null : language.getCode());
        row.put("defaultTimezone", timezone.getTimezone());
        row.put("defaultCountry", country.trim().toUpperCase());
        row.put(SOFT_DELETED_FIELD, false);
        row.put(CREATED_TIME, now);
        row.put(CREATED_ID, SYSTEM_USER_ID);
        row.put(CREATED_BY, SYSTEM_USER);
        row.put(UPDATED_TIME, now);
        row.put(UPDATED_ID, SYSTEM_USER_ID);
        row.put(UPDATED_BY, SYSTEM_USER);
        return row;
    }

    private static String column(String field) {
        return ModelManager.getModelField(MODEL, field).getColumnName();
    }

    private static String require(String value, String property) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException(property + " is required to create the platform tenant: every tenant "
                    + "carries one, and which value fits the operator's own workspace is the application's call.");
        }
        return value.trim();
    }
}
