package io.softa.starter.metadata.scanner;

import java.lang.annotation.Annotation;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import io.softa.framework.orm.annotation.Model;
import io.softa.framework.base.annotation.OptionSet;

/**
 * Helper for discovering classes annotated with {@link Model} or
 * {@link OptionSet} on the application classpath.
 *
 * <p>Wraps Spring's
 * {@link ClassPathScanningCandidateComponentProvider} with a
 * non-Spring-component configuration ({@code useDefaultFilters = false}, plus
 * {@code AnnotationTypeFilter}s for our two annotations) so the scanner finds
 * any annotated class, not just Spring-managed beans.
 *
 * <p>Used by {@code MetadataAnnotationScanner} (boot, write side) and
 * {@code MetadataAnnotationChecker} (post-boot, read side).
 */
@Slf4j
public final class ClasspathScannerSupport {

    private final Collection<String> basePackages;

    public ClasspathScannerSupport(Collection<String> basePackages) {
        this.basePackages = basePackages == null ? List.of() : basePackages;
    }

    /**
     * Find all classes annotated with {@link Model} in the configured packages.
     */
    public Set<Class<?>> findModelClasses() {
        return scan(Model.class);
    }

    /**
     * Find all classes (enums) annotated with {@link OptionSet} in the
     * configured packages.
     */
    public Set<Class<?>> findOptionSetEnums() {
        return scan(OptionSet.class);
    }

    private Set<Class<?>> scan(Class<? extends Annotation> annotation) {
        if (basePackages.isEmpty()) {
            log.warn("ClasspathScannerSupport invoked with empty base packages; "
                    + "annotation scan for @{} will return no results.",
                    annotation.getSimpleName());
            return Set.of();
        }

        // useDefaultFilters=false: we only want the type filter, not
        // Spring's stereotype defaults (@Component, @Service, ...).
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition def) {
                        // Accept any independent class (top-level, abstract,
                        // interface, enum) matching the type filter — bypass the
                        // default rule that excludes interfaces / inner classes.
                        return def.getMetadata().isIndependent();
                    }
                };
        provider.addIncludeFilter(new AnnotationTypeFilter(annotation));

        Set<Class<?>> found = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (String basePackage : basePackages) {
            provider.findCandidateComponents(basePackage).forEach(def -> {
                String className = def.getBeanClassName();
                if (className == null) {
                    return;
                }
                try {
                    // Default classloader = thread-context first: under a split-classloader
                    // setup (e.g. devtools' RestartClassLoader) application classes are not
                    // visible to this starter's own loader, and a miss here silently drops
                    // the model from management.
                    Class<?> clazz = Class.forName(className, false,
                            ClassUtils.getDefaultClassLoader());
                    found.add(clazz);
                } catch (ClassNotFoundException e) {
                    failures.add(className);
                }
            });
        }
        if (!failures.isEmpty()) {
            log.warn("Failed to load {} annotated class(es) during scan for @{}: {}",
                    failures.size(), annotation.getSimpleName(), failures);
        }
        return found;
    }
}
