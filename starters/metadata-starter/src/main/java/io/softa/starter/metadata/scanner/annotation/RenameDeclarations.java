package io.softa.starter.metadata.scanner.annotation;

import java.util.Map;

/**
 * The {@code renamedFrom} declarations parsed from the code side of a scan (the {@code @Model}/{@code @Field}
 * attribute). Code-side only — the from-db {@link AnnotationScanResult} carries {@link #empty()}
 * (the runtime catalog has no rename intent, only current state).
 *
 * <p>Keyed to line up with {@code DiffEngine}'s business keys so the rename pre-pass can pair a removed-old
 * row with the added-new one. The value is the single prior name (single-step, no chain):
 * <ul>
 *   <li>{@code modelOldNames}: current {@code modelName} → prior model name;</li>
 *   <li>{@code fieldOldNames}: current {@code "modelName.fieldName"} → prior <i>field</i> name (the model
 *       part is the field's current model — a concurrent model rename is resolved first by the diff).</li>
 * </ul>
 */
public record RenameDeclarations(
        Map<String, String> modelOldNames,
        Map<String, String> fieldOldNames) {

    public static RenameDeclarations empty() {
        return new RenameDeclarations(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return modelOldNames.isEmpty() && fieldOldNames.isEmpty();
    }
}
