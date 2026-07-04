package io.softa.starter.metadata.ddl;

import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.dialect.DdlDialectRegistry;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;
import io.softa.starter.metadata.scanner.diff.SchemaDiff.EntityDiff;
import io.softa.starter.metadata.scanner.diff.SchemaDiff.Modification;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-backed end-to-end tests for {@link DdlOrchestrator}, shared across DB dialects.
 *
 * <p>Concrete subclasses provide the H2 mode + matching production-style JDBC URL +
 * the dialect under test. The H2 datasource runs in the chosen compatibility mode so
 * the dialect's emitted SQL is exercised against a real JDBC engine.
 *
 * <p>Pre-setup SQL in these tests uses ANSI-compatible forms so it works in both
 * MySQL and PostgreSQL H2 modes; do not introduce vendor-specific syntax here.
 */
abstract class AbstractDdlOrchestratorTest {

    protected JdbcTemplate jdbcTemplate;
    protected DdlOrchestrator orchestrator;

    protected abstract String h2JdbcUrl();

    protected abstract String productionJdbcUrl();

    protected abstract DdlDialect createDialect(BuiltinDdlMetadataResolver resolver);

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(h2JdbcUrl());
        ds.setUser("sa");
        ds.setPassword("");
        this.jdbcTemplate = new JdbcTemplate(ds);

        BuiltinDdlMetadataResolver resolver = new BuiltinDdlMetadataResolver();
        DdlDialect dialect = createDialect(resolver);
        DdlDialectRegistry registry = new DdlDialectRegistry(List.of(dialect));

        this.orchestrator = new DdlOrchestrator(jdbcTemplate, registry, productionJdbcUrl());
    }

    // ---- fixtures -------------------------------------------------------

    private static SysModel customer() {
        SysModel m = new SysModel();
        m.setModelName("Customer");
        m.setLabel("Customer");
        m.setTableName("customer");
        m.setIdStrategy(IdStrategy.DB_AUTO_ID);
        m.setStorageType(StorageType.RDBMS);
        m.setMultiTenant(false);
        m.setBusinessKey(List.of("code"));
        m.setDescription("Customer master");
        return m;
    }

    private static SysModel stringIdModel() {
        SysModel m = new SysModel();
        m.setModelName("StrDoc");
        m.setLabel("StrDoc");
        m.setTableName("str_doc");
        m.setIdStrategy(IdStrategy.DISTRIBUTED_STRING);
        m.setStorageType(StorageType.RDBMS);
        m.setMultiTenant(false);
        m.setBusinessKey(List.of("code"));
        m.setDescription("String-id doc");
        return m;
    }

    private static SysModel orderModel() {
        SysModel m = new SysModel();
        m.setModelName("Order");
        m.setLabel("Order");
        m.setTableName("biz_order");
        m.setIdStrategy(IdStrategy.DB_AUTO_ID);
        m.setStorageType(StorageType.RDBMS);
        m.setMultiTenant(false);
        return m;
    }

    private static SysField field(String modelName, String fieldName, FieldType type, Integer length, boolean required) {
        return fieldWithColumn(modelName, fieldName, fieldName, type, length, required);
    }

    /**
     * A TO_ONE foreign-key field. {@code relatedField == null} = id-based (default,
     * column should mirror the related id → BIGINT); a non-id relatedField is
     * reference-by-code (column mirrors that field → VARCHAR(n)). The FK carries no
     * length of its own — the orchestrator derives it from the referenced column.
     */
    private static SysField referenceFk(String modelName, String fieldName, FieldType type,
                                        String relatedModel, String relatedField) {
        SysField f = fieldWithColumn(modelName, fieldName, fieldName, type, null, false);
        f.setRelatedModel(relatedModel);
        f.setRelatedField(relatedField);
        return f;
    }

    private static SysField fieldWithColumn(String modelName, String fieldName, String columnName,
                                            FieldType type, Integer length, boolean required) {
        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(fieldName);
        f.setColumnName(columnName);
        f.setLabel(fieldName);
        f.setFieldType(type);
        if (length != null) f.setLength(length);
        f.setRequired(required);
        f.setDescription(fieldName);
        return f;
    }

    /** The PK field the parser now always emits; CREATE-TABLE fixtures supply it explicitly. */
    private static SysField idField(String modelName) {
        return idField(modelName, FieldType.LONG);
    }

    private static SysField idField(String modelName, FieldType type) {
        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName("id");
        f.setColumnName("id");
        f.setFieldType(type);
        if (type == FieldType.STRING) {
            f.setLength(24);
        }
        f.setRequired(true);
        f.setDescription("ID");
        return f;
    }

    private static SysModelIndex idx(String modelName, String indexName, List<String> fields, boolean unique) {
        SysModelIndex i = new SysModelIndex();
        i.setModelName(modelName);
        i.setIndexName(indexName);
        i.setIndexFields(new ArrayList<>(fields));
        i.setUniqueIndex(unique);
        return i;
    }

    /**
     * Build a diff and apply it. The {@code allCodeModels} / {@code allCodeFields}
     * args are constructed from the union of added + modified (fromCode side)
     * models and fields — mirroring what the real scanner would supply.
     */
    private void applyDiff(SchemaDiff diff, List<SysModel> allCodeModels, List<SysField> allCodeFields) {
        // Mirror the production order: the scanner stamps TO_ONE FK physical types onto the full
        // code field set before diff/render (ReferenceColumnResolver). The render then reads the
        // stored relatedFieldType — no resolution happens at render time.
        ReferenceColumnResolver.stampSysFields(allCodeFields);
        orchestrator.apply(diff, allCodeModels, allCodeFields);
    }

    private static SchemaDiff diffOf(
            List<SysModel> addedModels,
            List<SysField> addedFields,
            List<Modification<SysField>> modifiedFields,
            List<SysField> removedFields,
            List<SysModel> removedModels) {
        return new SchemaDiff(
                new EntityDiff<>(addedModels, removedModels, List.of()),
                new EntityDiff<>(addedFields, removedFields, modifiedFields),
                EntityDiff.<SysOptionSet>empty(),
                EntityDiff.<SysOptionItem>empty());
    }

    private static SchemaDiff diffWithIndexes(
            List<SysModel> addedModels,
            List<SysField> addedFields,
            List<SysModelIndex> addedIndexes,
            List<SysModelIndex> removedIndexes) {
        return new SchemaDiff(
                new EntityDiff<>(addedModels, List.of(), List.of()),
                new EntityDiff<>(addedFields, List.of(), List.of()),
                EntityDiff.<SysOptionSet>empty(),
                EntityDiff.<SysOptionItem>empty(),
                new EntityDiff<>(addedIndexes, removedIndexes, List.of()));
    }

    /** Collect all from-code models from a diff's added + modified buckets. */
    private static List<SysModel> codeModels(SchemaDiff diff, SysModel... extra) {
        List<SysModel> out = new ArrayList<>(diff.models().added());
        diff.models().modified().forEach(m -> out.add(m.fromCode()));
        out.addAll(List.of(extra));
        return out;
    }

    /** Collect all from-code fields from a diff's added + modified buckets. */
    private static List<SysField> codeFields(SchemaDiff diff, SysField... extra) {
        List<SysField> out = new ArrayList<>(diff.fields().added());
        diff.fields().modified().forEach(m -> out.add(m.fromCode()));
        out.addAll(List.of(extra));
        return out;
    }

    // ---- scenario A: CREATE TABLE auto-executed --------------------------

    @Test
    void createTable_isAutoExecuted_whenNewModelInDiff() {
        SysModel customer = customer();
        SysField id = idField("Customer");
        SysField name = field("Customer", "name", FieldType.STRING, 100, true);
        SysField email = field("Customer", "email", FieldType.STRING, 255, false);

        SchemaDiff diff = diffOf(
                List.of(customer),
                List.of(id, name, email),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertTableExists("customer");
        assertColumnExists("customer", "id");
        assertColumnExists("customer", "name");
        assertColumnExists("customer", "email");
    }

    @Test
    void createTable_distributedStringId_emitsVarcharPrimaryKey() {
        SysModel doc = stringIdModel();
        SysField id = idField("StrDoc", FieldType.STRING);
        SysField code = field("StrDoc", "code", FieldType.STRING, 32, true);

        SchemaDiff diff = diffOf(
                List.of(doc),
                List.of(id, code),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertColumnExists("str_doc", "id");
        // A STRING id field renders as VARCHAR (H2 reports "CHARACTER VARYING"),
        // never BIGINT — a 13-char Radix36 id cannot live in a 64-bit integer column.
        String idType = queryColumnType("str_doc", "id").toUpperCase();
        assertTrue(idType.contains("CHAR"),
                "DISTRIBUTED_STRING id column should be VARCHAR, got " + idType);
    }

    // ---- scenario B: ADD COLUMN auto-executed ---------------------------

    @Test
    void addColumn_isAutoExecuted_whenNewFieldOnExistingModel() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(100))");

        SysField lastVisit = field("Customer", "last_visit", FieldType.DATE_TIME, null, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(lastVisit),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        assertColumnExists("customer", "last_visit");
        // DATE_TIME → DATETIME on MySQL, TIMESTAMP on PostgreSQL; accept both
        String columnType = queryColumnType("customer", "last_visit");
        assertTrue(columnType.equalsIgnoreCase("DATETIME") || columnType.equalsIgnoreCase("TIMESTAMP"),
                "expected DATETIME-ish for DATE_TIME column, got " + columnType);
    }

    // ---- scenario C: MODIFY COLUMN auto-executed ------------------------

    @Test
    void modifyColumn_isAutoExecuted_whenFieldLengthChanged() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(64))");

        SysField emailV1 = field("Customer", "email", FieldType.STRING, 64, false);
        SysField emailV2 = field("Customer", "email", FieldType.STRING, 256, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(),
                List.of(new Modification<>(emailV2, emailV1)),
                List.of(), List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        Integer length = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                Integer.class, "customer", "email");
        assertEquals(256, length, "MODIFY COLUMN should have widened email to 256");
    }

    // ---- scenario D: DROP COLUMN is NOT auto-executed (warn-only) -------

    @Test
    void dropColumn_isNotAutoExecuted_columnRemains() {
        jdbcTemplate.execute("""
                CREATE TABLE customer
                (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(64), legacy VARCHAR(100))
                """);

        SysField legacy = field("Customer", "legacy", FieldType.STRING, 100, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(), List.of(),
                List.of(legacy),
                List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        // The legacy column MUST still be present — policy says no auto-DROP
        assertColumnExists("customer", "legacy");
    }

    // ---- scenario E: DROP TABLE is NOT auto-executed (warn-only) --------

    @Test
    void dropTable_isNotAutoExecuted_tableRemains() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY)");

        SchemaDiff diff = diffOf(
                List.of(), List.of(), List.of(), List.of(),
                List.of(customer()));
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertTableExists("customer");
    }

    // ---- scenario G: tableName change is NOT auto-executed (warn-only) ---

    @Test
    void tableRename_isNotAutoExecuted_oldTableRemains() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY)");

        SysModel before = customer();                   // tableName "customer"
        SysModel after = customer();
        after.setTableName("customer_v2");

        SchemaDiff diff = new SchemaDiff(
                new EntityDiff<>(List.of(), List.of(), List.of(new Modification<>(after, before))),
                EntityDiff.<SysField>empty(),
                EntityDiff.<SysOptionSet>empty(),
                EntityDiff.<SysOptionItem>empty());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        // Table renames carry data — warn-only, like drops: the physical table
        // keeps the old name and no new table appears.
        assertTableExists("customer");
        Integer v2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = LOWER(?)",
                Integer.class, "customer_v2");
        assertEquals(0, v2, "rename must not be auto-executed");
    }

    // ---- scenario H: declared field rename → CHANGE COLUMN auto-executed -

    @Test
    void declaredFieldRename_isAutoExecuted_columnMovedAndDataPreserved() {
        jdbcTemplate.execute(
                "CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, acct_no VARCHAR(100))");
        jdbcTemplate.execute("INSERT INTO customer (id, acct_no) VALUES (1, 'A-001')");

        // @RenamedFrom acctNo → accountNumber (column acct_no → account_number).
        // The DiffEngine emits this as a single Modification(kind=RENAME), fromDb
        // carrying the prior column the renderer reads for CHANGE/RENAME COLUMN.
        SysField oldField = fieldWithColumn("Customer", "acctNo", "acct_no", FieldType.STRING, 100, false);
        SysField newField = fieldWithColumn("Customer", "accountNumber", "account_number", FieldType.STRING, 100, false);
        SchemaDiff diff = diffOf(
                List.of(), List.of(),
                List.of(new Modification<>(newField, oldField, SchemaDiff.Kind.RENAME)),
                List.of(), List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        // Unlike a drop+add, the rename MOVES the column in place: new present,
        // old gone, and the existing row's value carried over.
        assertColumnExists("customer", "account_number");
        Integer oldCol = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                Integer.class, "customer", "acct_no");
        assertEquals(0, oldCol, "declared rename must move the column, not duplicate it");
        String carried = jdbcTemplate.queryForObject(
                "SELECT account_number FROM customer WHERE id = 1", String.class);
        assertEquals("A-001", carried, "declared rename must preserve the existing row data");
    }

    // ---- scenario I: declared model rename → RENAME TABLE auto-executed ---

    @Test
    void declaredModelRename_isAutoExecuted_tableMovedAndDataPreserved() {
        jdbcTemplate.execute("CREATE TABLE old_customer (id BIGINT NOT NULL PRIMARY KEY)");
        jdbcTemplate.execute("INSERT INTO old_customer (id) VALUES (1)");

        SysModel before = customer();           // re-pointed to the prior name below
        before.setModelName("OldCustomer");
        before.setTableName("old_customer");
        SysModel after = customer();            // modelName "Customer", tableName "customer"

        SchemaDiff diff = new SchemaDiff(
                new EntityDiff<>(List.of(), List.of(),
                        List.of(new Modification<>(after, before, SchemaDiff.Kind.RENAME))),
                EntityDiff.<SysField>empty(),
                EntityDiff.<SysOptionSet>empty(),
                EntityDiff.<SysOptionItem>empty());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        // Declared (unlike scenario G's bare tableName change) → RENAME TABLE
        // auto-executes: new table present with its data, old name gone.
        assertTableExists("customer");
        Integer oldTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = LOWER(?)",
                Integer.class, "old_customer");
        assertEquals(0, oldTable, "declared model rename must move the table, not copy it");
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customer", Integer.class);
        assertEquals(1, rows, "declared model rename must preserve table data");
    }

    // ---- scenario F: mixed ALTER — adds executed, drops warned ---------

    @Test
    void mixedAlter_addsExecutedAndDropsWarned() {
        jdbcTemplate.execute("""
                CREATE TABLE customer
                (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(64), legacy VARCHAR(100))
                """);

        SysField newField = field("Customer", "phone", FieldType.STRING, 32, false);
        SysField droppedField = field("Customer", "legacy", FieldType.STRING, 100, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(newField),
                List.of(),
                List.of(droppedField),
                List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        assertColumnExists("customer", "phone");
        assertColumnExists("customer", "legacy");
    }

    // ---- reference-by-code: FK column mirrors the referenced column ------

    @Test
    void createTable_referenceByCodeFk_rendersVarcharMirroringReferencedCode() {
        // Customer.code is VARCHAR(32) (single-column businessKey). Order.customerCode is a
        // MANY_TO_ONE whose relatedField="code" → the FK column must render VARCHAR(32),
        // not BIGINT, so it can physically hold the code value.
        SysModel customer = customer();
        SysField custId = idField("Customer");
        SysField custCode = field("Customer", "code", FieldType.STRING, 32, true);

        SysModel order = orderModel();
        SysField orderId = idField("Order");
        SysField customerCode = referenceFk("Order", "customer_code", FieldType.MANY_TO_ONE, "Customer", "code");

        SchemaDiff diff = diffOf(
                List.of(customer, order),
                List.of(custId, custCode, orderId, customerCode),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertColumnExists("biz_order", "customer_code");
        String type = queryColumnType("biz_order", "customer_code").toUpperCase();
        assertTrue(type.contains("CHAR"),
                "reference-by-code FK should be VARCHAR, got " + type);
        Integer len = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                Integer.class, "biz_order", "customer_code");
        assertEquals(32, len, "FK column must mirror the referenced code length");
    }

    @Test
    void createTable_idBasedManyToOne_stillRendersBigint() {
        // Regression: a default (relatedField=id) MANY_TO_ONE to a Long-id model stays
        // BIGINT — reference-by-code resolution must be zero-diff for existing relations.
        SysModel customer = customer();
        SysField custId = idField("Customer");
        SysField custCode = field("Customer", "code", FieldType.STRING, 32, true);

        SysModel order = orderModel();
        SysField orderId = idField("Order");
        SysField customerId = referenceFk("Order", "customer_id", FieldType.MANY_TO_ONE, "Customer", null);

        SchemaDiff diff = diffOf(
                List.of(customer, order),
                List.of(custId, custCode, orderId, customerId),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertColumnExists("biz_order", "customer_id");
        String type = queryColumnType("biz_order", "customer_id").toUpperCase();
        assertTrue(type.contains("BIGINT") || type.contains("INTEGER"),
                "id-based MANY_TO_ONE FK should stay BIGINT, got " + type);
    }

    @Test
    void createTable_oneToOneReferenceByCode_rendersVarcharMirroringReferencedCode() {
        // The column-mirror applies to all TO_ONE types: a ONE_TO_ONE whose relatedField is a
        // code must also render VARCHAR(n), not BIGINT.
        SysModel customer = customer();
        SysField custId = idField("Customer");
        SysField custCode = field("Customer", "code", FieldType.STRING, 32, true);

        SysModel order = orderModel();
        SysField orderId = idField("Order");
        SysField customerCode = referenceFk("Order", "customer_code", FieldType.ONE_TO_ONE, "Customer", "code");

        SchemaDiff diff = diffOf(
                List.of(customer, order),
                List.of(custId, custCode, orderId, customerCode),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        String type = queryColumnType("biz_order", "customer_code").toUpperCase();
        assertTrue(type.contains("CHAR"),
                "ONE_TO_ONE reference-by-code FK should be VARCHAR, got " + type);
    }

    // ---- @Index scenarios -----------------------------------------------

    @Test
    void createTable_withIndexes_emitsIndexes() {
        SysModel customer = customer();
        SysField id = idField("Customer");
        SysField code = field("Customer", "code", FieldType.STRING, 32, true);
        SysField status = field("Customer", "status", FieldType.STRING, 20, false);
        SysModelIndex uk = idx("Customer", "uk_customer_code", List.of("code"), true);
        SysModelIndex idxStatus = idx("Customer", "idx_customer_status", List.of("status"), false);

        SchemaDiff diff = diffWithIndexes(
                List.of(customer),
                List.of(id, code, status),
                List.of(uk, idxStatus),
                List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        assertTableExists("customer");
        assertIndexExists("customer", "uk_customer_code");
        assertIndexExists("customer", "idx_customer_status");
    }

    @Test
    void addIndex_isAutoExecuted_whenNewIndexOnExistingTable() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(255))");

        SysField email = field("Customer", "email", FieldType.STRING, 255, false);
        SysModelIndex emailIdx = idx("Customer", "uk_customer_email", List.of("email"), true);
        SchemaDiff diff = diffWithIndexes(
                List.of(), List.of(), List.of(emailIdx), List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff, email));

        assertIndexExists("customer", "uk_customer_email");
    }

    @Test
    void dropIndex_isNotAutoExecuted_indexRemains() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(255))");
        // ANSI form works in both MySQL and PostgreSQL H2 modes
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_customer_email ON customer (email)");

        SysModelIndex emailIdx = idx("Customer", "uk_customer_email", List.of("email"), true);
        SchemaDiff diff = diffWithIndexes(
                List.of(), List.of(), List.of(), List.of(emailIdx));
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        // Index must still exist — policy says no auto-DROP
        assertIndexExists("customer", "uk_customer_email");
    }

    // ---- regression: custom tableName on ALTER path -----------------------

    @Test
    void addColumn_usesCustomTableName_whenModelNotInDiff() {
        // Model uses a non-default tableName: "BizOrder" → "biz_order" (not "biz_order"
        // via snake_case of class name which would be "biz_order" — use an intentionally
        // different name to prove the lookup works).
        SysModel order = new SysModel();
        order.setModelName("Order");
        order.setLabel("Order");
        order.setTableName("biz_order");  // custom: not snake_case("Order") = "order"
        order.setIdStrategy(IdStrategy.DB_AUTO_ID);
        order.setStorageType(StorageType.RDBMS);

        // Pre-create the table with the custom name
        jdbcTemplate.execute("CREATE TABLE biz_order (id BIGINT NOT NULL PRIMARY KEY, total DECIMAL(10,2))");

        // Diff has only a field change — model is not added/modified
        SysField shippedAt = field("Order", "shipped_at", FieldType.DATE_TIME, null, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(shippedAt),
                List.of(), List.of(), List.of());

        // Pass the full model in allCodeModels so DdlPolicy can resolve tableName
        applyDiff(diff, codeModels(diff, order), codeFields(diff));

        // Column must be added to biz_order, not to "order"
        assertColumnExists("biz_order", "shipped_at");
    }

    // ---- regression: index with custom columnName on existing field ------

    @Test
    void addIndex_resolvesCustomColumnName_forPreExistingField() {
        // Model with a field that has a non-default columnName
        SysModel customer = customer();
        SysField custName = fieldWithColumn("Customer", "customerName", "cust_name",
                FieldType.STRING, 100, true);

        jdbcTemplate.execute(
                "CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, cust_name VARCHAR(100))");

        // Adding an index that references the field by fieldName "customerName",
        // which must resolve to column "cust_name" (not snake_case "customer_name")
        SysModelIndex idx = idx("Customer", "idx_customer_name", List.of("customerName"), false);
        SchemaDiff diff = diffWithIndexes(
                List.of(), List.of(), List.of(idx), List.of());

        // Pass custName in allCodeFields so the index column is resolved correctly
        applyDiff(diff, codeModels(diff, customer), codeFields(diff, custName));

        assertIndexExists("customer", "idx_customer_name");
    }

    // ---- regression: per-statement granularity ----------------------------

    @Test
    void addTwoColumns_firstAlreadyExists_secondIsStillAdded() {
        // The first ADD COLUMN degrades to "already applied" (someone ran the SQL
        // by hand); the second must still execute. Before per-change rendering the
        // two adds shared one ALTER/one classification, so the duplicate on the
        // first silently swallowed the second — permanently, because the sys_* rows
        // commit right after and the next boot's diff is empty.
        jdbcTemplate.execute(
                "CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(64))");

        SysField email = field("Customer", "email", FieldType.STRING, 64, false);
        SysField phone = field("Customer", "phone", FieldType.STRING, 32, false);
        SchemaDiff diff = diffOf(
                List.of(),
                List.of(email, phone),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff, customer()), codeFields(diff));

        assertColumnExists("customer", "phone");
    }

    @Test
    void updatedIndex_isRebuilt_whenDefinitionChanged() {
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY,"
                + " email VARCHAR(255), phone VARCHAR(32))");
        jdbcTemplate.execute("CREATE INDEX idx_customer_email ON customer (email)");

        SysField email = field("Customer", "email", FieldType.STRING, 255, false);
        SysField phone = field("Customer", "phone", FieldType.STRING, 32, false);
        SysModelIndex before = idx("Customer", "idx_customer_email", List.of("email"), false);
        SysModelIndex after = idx("Customer", "idx_customer_email", List.of("email", "phone"), false);
        SchemaDiff diff = diffWithModifiedIndex(after, before);

        applyDiff(diff, codeModels(diff, customer()), codeFields(diff, email, phone));

        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(INDEX_NAME) = LOWER(?)",
                Integer.class, "customer", "idx_customer_email");
        assertEquals(2, columns, "rebuilt index must carry the new two-column definition");
    }

    @Test
    void updatedIndex_missingPhysicalIndex_addStillRuns() {
        // The rebuild's DROP INDEX finds nothing (drift: never created / manually
        // dropped) → degrades per-statement; the ADD half must still execute.
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY,"
                + " email VARCHAR(255), phone VARCHAR(32))");

        SysField email = field("Customer", "email", FieldType.STRING, 255, false);
        SysField phone = field("Customer", "phone", FieldType.STRING, 32, false);
        SysModelIndex before = idx("Customer", "idx_customer_email", List.of("email"), false);
        SysModelIndex after = idx("Customer", "idx_customer_email", List.of("email", "phone"), false);
        SchemaDiff diff = diffWithModifiedIndex(after, before);

        applyDiff(diff, codeModels(diff, customer()), codeFields(diff, email, phone));

        assertIndexExists("customer", "idx_customer_email");
    }

    @Test
    void modifyColumn_onMissingColumn_failsFast() {
        // A MODIFY targeting a column that does not exist is genuine drift (e.g. a
        // hand-run DROP) — it must fail the boot, not be mistaken for an applied
        // rename. The unknown-column tolerance is scoped to declared renames only.
        jdbcTemplate.execute("CREATE TABLE customer (id BIGINT NOT NULL PRIMARY KEY)");

        SysField v1 = field("Customer", "vanished", FieldType.STRING, 64, false);
        SysField v2 = field("Customer", "vanished", FieldType.STRING, 256, false);
        SchemaDiff diff = diffOf(
                List.of(), List.of(),
                List.of(new Modification<>(v2, v1)),
                List.of(), List.of());

        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> applyDiff(diff, codeModels(diff, customer()), codeFields(diff)));
    }

    // ---- regression: string defaultValue rendered as quoted literal -------

    @Test
    void createTable_stringDefaultValue_isQuotedLiteral() {
        SysModel customer = customer();
        SysField id = idField("Customer");
        SysField status = field("Customer", "status", FieldType.STRING, 20, false);
        status.setDefaultValue("ACTIVE");   // raw VALUE — must render as 'ACTIVE'

        SchemaDiff diff = diffOf(
                List.of(customer), List.of(id, status),
                List.of(), List.of(), List.of());
        applyDiff(diff, codeModels(diff), codeFields(diff));

        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                String.class, "customer", "status");
        assertNotNull(columnDefault);
        assertTrue(columnDefault.contains("ACTIVE"),
                "string default must survive as a literal, got: " + columnDefault);
        // And the value actually applies on INSERT.
        jdbcTemplate.execute("INSERT INTO customer (id) VALUES (1)");
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM customer WHERE id = 1", String.class));
    }

    private static SchemaDiff diffWithModifiedIndex(SysModelIndex fromCode, SysModelIndex fromDb) {
        return new SchemaDiff(
                EntityDiff.<SysModel>empty(),
                EntityDiff.<SysField>empty(),
                EntityDiff.<SysOptionSet>empty(),
                EntityDiff.<SysOptionItem>empty(),
                new EntityDiff<>(List.of(), List.of(), List.of(new Modification<>(fromCode, fromDb))));
    }

    // ---- helpers --------------------------------------------------------

    private void assertTableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = LOWER(?)",
                Integer.class, table);
        assertNotNull(count);
        assertTrue(count >= 1, "table " + table + " should exist");
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                Integer.class, table, column);
        assertNotNull(count);
        assertTrue(count >= 1, "column " + table + "." + column + " should exist");
    }

    private String queryColumnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                String.class, table, column);
    }

    /**
     * Match by exact name OR by H2's UNIQUE-index synthetic suffix
     * (e.g. {@code uk_customer_email_INDEX_2}). The suffix shows up in both H2 modes
     * for unique indexes, so a single OR-clause covers both dialects.
     */
    private void assertIndexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE LOWER(TABLE_NAME) = LOWER(?) "
                        + "AND (LOWER(INDEX_NAME) = LOWER(?) OR LOWER(INDEX_NAME) LIKE LOWER(?))",
                Integer.class, table, indexName, indexName + "_index_%");
        assertNotNull(count);
        assertTrue(count >= 1,
                "index " + table + "." + indexName + " should exist; existing: "
                        + jdbcTemplate.queryForList(
                                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                                        + "WHERE LOWER(TABLE_NAME) = LOWER(?)",
                                String.class, table));
    }
}
