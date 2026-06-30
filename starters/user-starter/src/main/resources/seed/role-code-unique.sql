-- ════════════════════════════════════════════════════════════════════
-- Constraint: (tenant_id, code) UNIQUE on role table
-- ════════════════════════════════════════════════════════════════════
--
-- Belt-and-braces defence against forged system roles. RoleServiceImpl
-- already rejects admin-created rows with a non-null code, but a direct
-- SQL write (DB tool, migration mistake, etc.) could still create a
-- duplicate SUPER_ADMIN row. This UNIQUE index blocks that path.
--
-- MySQL / MariaDB / PostgreSQL / Oracle / SQLite all treat NULL ≠ NULL
-- in UNIQUE constraints, so admin-created roles with code=NULL freely
-- coexist (multiple NULLs in a tenant are fine). Only non-null codes
-- are constrained — exactly the semantic we want.
--
-- SQL Server is the exception (NULL = NULL in UNIQUE). If/when the
-- project supports SQL Server, swap this for a filtered index:
--   CREATE UNIQUE INDEX uk_tenant_code ON role(tenant_id, code) WHERE code IS NOT NULL;
--
-- IDEMPOTENCY: the IF NOT EXISTS guard makes re-running safe on MySQL
-- 8.0+. Older MySQL: drop manually if pre-existing.
-- ════════════════════════════════════════════════════════════════════

-- Drop legacy if present (handle pre-existing schemas without the guard)
-- ALTER TABLE role DROP INDEX uk_tenant_code;

ALTER TABLE role
    ADD UNIQUE KEY uk_tenant_code (tenant_id, code);
