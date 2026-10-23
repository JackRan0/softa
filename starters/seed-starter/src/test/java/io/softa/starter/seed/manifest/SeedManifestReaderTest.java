package io.softa.starter.seed.manifest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedManifestReaderTest {

    private static final Predicate<String> EVERY_FILE_EXISTS = path -> true;

    @Test
    void readsBothListsInOrderWithTheLevelFromTheDirectory() {
        SeedManifest manifest = read("""
                platform:
                  - data-system/Navigation.Hcm.json
                  - data-platform/MailTemplate.Platform.json
                  - data-system/Permission.Hcm.json
                tenant:
                  - files:
                      - data-tenant/SysSequence.Default.json
                      - file: data-tenant/Role.Tenant.json
                        overwrite: true
                  - module: leave
                    countries: [NZ]
                    files:
                      - data-tenant/LeaveType.NZ.json
                """);

        // Load order is the list's order, whichever directory a platform file sits in.
        assertEquals(List.of("Navigation.Hcm.json", "MailTemplate.Platform.json", "Permission.Hcm.json"),
                names(manifest.platformFiles()));
        assertEquals(List.of("SysSequence.Default.json", "Role.Tenant.json", "LeaveType.NZ.json"),
                names(manifest.tenantFiles()));
        assertEquals(SeedLevel.PLATFORM_GLOBAL, manifest.file("Navigation.Hcm.json").orElseThrow().level());
        assertEquals(SeedLevel.PLATFORM_TENANT, manifest.file("MailTemplate.Platform.json").orElseThrow().level());
        SeedFile role = manifest.file("Role.Tenant.json").orElseThrow();
        assertTrue(role.overwrite());
        assertTrue(role.reloadsWhenChanged());
        assertFalse(manifest.file("SysSequence.Default.json").orElseThrow().reloadsWhenChanged());
        // Platform files reload whenever they change, without saying so.
        assertTrue(manifest.file("Navigation.Hcm.json").orElseThrow().reloadsWhenChanged());
        // A group's module and countries are its files'.
        SeedFile leaveType = manifest.file("LeaveType.NZ.json").orElseThrow();
        assertEquals("leave", leaveType.module());
        assertEquals(List.of("NZ"), leaveType.countries());
        assertNull(role.module());
        assertEquals("data-tenant/LeaveType.NZ.json", leaveType.path());
    }

    @Test
    void aTenantIsDueTheFilesOfItsPlanAndCountries() {
        SeedManifest manifest = read("""
                tenant:
                  - files:
                      - data-tenant/Base.json
                  - countries: [NZ]
                    files:
                      - data-tenant/Base.NZ.json
                  - module: leave
                    files:
                      - data-tenant/Leave.json
                """);

        assertEquals(List.of("Base.json"), names(manifest.tenantFiles(Set.of("core-hr"), Set.of("SG"))));
        assertEquals(List.of("Base.json", "Base.NZ.json", "Leave.json"),
                names(manifest.tenantFiles(Set.of("leave"), Set.of("SG", "NZ"))));
        // No plans in use: every module's files are due.
        assertEquals(List.of("Base.json", "Leave.json"), names(manifest.tenantFiles(null, Set.of())));
    }

    @Test
    void everythingWrongIsReportedAtOnce() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/A.json
                  - file: data-system/B.json
                    overwrite: true
                  - data-tenant/C.json
                tenant:
                  - module: leave
                    countrys: [SG]
                    files:
                      - data-tenant/D.json
                      - data-system/E.json
                      - file: data-tenant/F.json
                        overwrite: sometimes
                  - countries: [SG, SG]
                    files:
                      - data-tenant/A.json
                  - module: payroll
                tenants: []
                """));

        List<String> problems = failure.getProblems();
        assertHas(problems, "the manifest has an unknown key 'tenants'");
        assertHas(problems, "'data-system/B.json' is a platform file, and platform files always reload");
        assertHas(problems, "platform file 'data-tenant/C.json' is not in data-system/ or data-platform/");
        assertHas(problems, "tenant group #1 has an unknown key 'countrys'");
        assertHas(problems, "tenant file 'data-system/E.json' is not in data-tenant/");
        assertHas(problems, "overwrite must be true or false");
        assertHas(problems, "lists 'SG' twice");
        assertHas(problems, "file 'A.json' is registered twice");
        assertHas(problems, "tenant group #3 has no files");
    }

    @Test
    void aRepeatedKeyIsRefusedRatherThanDroppingTheFirstOnesFiles() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/A.json
                tenant:
                  - files:
                      - data-tenant/B.json
                platform:
                  - data-system/C.json
                """));

        assertHas(failure.getProblems(), "the manifest repeats a key");
    }

    @Test
    void whatYamlWouldMisreadIsRefused() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                platform:
                  - data-system/Rules.xml
                tenant:
                  - module: [leave, payroll]
                    files:
                      - data-tenant/A.json
                  - countries: [NZ, NO, sg]
                    files:
                      - data-tenant/B.json
                """));

        List<String> problems = failure.getProblems();
        // A list is not one module: as a name, "[leave, payroll]" matches no tenant's plan.
        assertHas(problems, "tenant group #1: module must be one module name");
        // YAML reads an unquoted NO (Norway) as false, and a lower-case code never matches a country.
        assertHas(problems, "has 'false', which YAML reads from an unquoted NO");
        assertHas(problems, "has 'sg', which is not a two-letter upper-case country code");
        assertHas(problems, "file 'data-system/Rules.xml' is not a .json or .csv file");
    }

    @Test
    void theOldListOfPackagesIsRefused() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> read("""
                - key: base
                  files:
                    - file: SysSequence.Default.json
                      level: TENANT
                """));

        assertHas(failure.getProblems(), "must be a map with a `platform` list and a `tenant` list");
    }

    @Test
    void aRegisteredFileMustBeOnTheClasspath() {
        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> SeedManifestReader.read(
                stream("""
                        tenant:
                          - files:
                              - data-tenant/Missing.json
                        """), "test", path -> !path.equals("data-tenant/Missing.json")));

        assertEquals(List.of("file 'data-tenant/Missing.json' is not on the classpath"), failure.getProblems());
    }

    private static void assertHas(List<String> problems, String fragment) {
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(fragment)), problems.toString());
    }

    private static SeedManifest read(String yaml) {
        return SeedManifestReader.read(stream(yaml), "test", EVERY_FILE_EXISTS);
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(List<SeedFile> files) {
        return files.stream().map(SeedFile::file).toList();
    }
}
