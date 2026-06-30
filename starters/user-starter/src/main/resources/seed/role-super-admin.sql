-- ════════════════════════════════════════════════════════════════════
-- Seed: SUPER_ADMIN Role per tenant
-- ════════════════════════════════════════════════════════════════════
--
-- Inserts the system-reserved SUPER_ADMIN role for every existing tenant
-- that doesn't already have one. Idempotent — safe to re-run.
--
-- WHEN TO RUN
--   • Once on every cluster upgrade after the Role.code column lands.
--   • As part of new-tenant onboarding (or — preferred — wire into
--     TenantService.onCreate to do this automatically).
--
-- AFTER SEEDING
--   • Grant SUPER_ADMIN to at least one bootstrap admin user via
--     INSERT INTO user_role_rel (tenant_id, user_id, role_id, source)
--     VALUES (<tenantId>, <userId>, <newRoleId>, 'Manual');
--   • That user then short-circuits every permission / scope check —
--     see PermissionInfoEnricherImpl.enrich.
--
-- WHY IT'S SAFE TO HARDCODE name='Super Admin'
--   The Role unique key is (tenant_id, name); collision yields a duplicate
--   row only when an admin already named one of their roles "Super Admin"
--   — the INSERT … SELECT WHERE NOT EXISTS guard prevents that.
-- ════════════════════════════════════════════════════════════════════

INSERT INTO role (tenant_id, name, code, description, active, created_time, created_by, updated_time, updated_by, deleted)
SELECT t.id, 'Super Admin', 'SUPER_ADMIN',
       'System reserved — full bypass of permission & data-scope checks. Cannot be deleted / renamed / deactivated.',
       1, NOW(), 'system', NOW(), 'system', 0
FROM tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM role r
    WHERE r.tenant_id = t.id AND r.code = 'SUPER_ADMIN'
);
