package io.softa.starter.metadata.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for metadata starter.
 *
 * <p>Bound to {@code system.metadata.*} in {@code application.yml}.
 *
 * @param scannerScope ordered list of regex patterns, each
 *     {@linkplain java.util.regex.Matcher#matches() full-matched} against a
 *     class's {@linkplain Class#getPackageName() package name}, deciding which
 *     {@code @Model} / {@code @OptionSet} declarations the
 *     {@code MetadataAnnotationScanner} reconciles into {@code sys_*} (and
 *     applies DDL for) at boot.
 *     <ul>
 *       <li>A sole entry {@code "*"} ⇒ match every package (manage all).</li>
 *       <li>Empty / unset ⇒ the scanner manages nothing and the
 *           {@code MetadataAnnotationChecker} instead observes the whole
 *           catalog read-only and WARNs on drift (the safe default).</li>
 *       <li>A partial list ⇒ the scanner reconciles only in-scope packages;
 *           rows outside the scope are never read, written, or deleted (lets
 *           developers share one dev database on disjoint package areas).</li>
 *     </ul>
 *     Dots are regex metacharacters — write {@code io\.softa\.foo.*} to include
 *     sub-packages, {@code io\.softa\.foo} for that exact package only.
 *     <b>Never set a non-empty scope on a production runtime.</b>
 * @param scanBasePackages classpath roots the scanner / checker <b>discover</b>
 *     {@code @Model} / {@code @OptionSet} classes under, in addition to the
 *     application's own {@code AutoConfigurationPackages}. Defaults to
 *     {@code ["io.softa"]} so framework / starter models (system models,
 *     reference data, framework enums) are discoverable out of the box.
 *     Discovery is not management: {@code scanner-scope} still decides what
 *     gets reconciled — with an empty scope (production default) nothing is
 *     ever written regardless of what is discovered.
 * @param publicKey base64-encoded X.509 Ed25519 public key trusted by this
 *     runtime when verifying signatures on studio-issued requests. Blank /
 *     unset means signature verification is disabled (the filter bean is not
 *     registered).
 */
@ConfigurationProperties("system.metadata")
public record MetadataProperties(
        List<String> scannerScope,
        List<String> scanBasePackages,
        String publicKey) {

    /** Default discovery root: the framework + starter + app namespace. */
    public static final List<String> DEFAULT_SCAN_BASE_PACKAGES = List.of("io.softa");

    public MetadataProperties {
        scannerScope = scannerScope == null ? List.of() : List.copyOf(scannerScope);
        scanBasePackages = scanBasePackages == null
                ? DEFAULT_SCAN_BASE_PACKAGES : List.copyOf(scanBasePackages);
    }
}
