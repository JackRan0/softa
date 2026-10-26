package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;

/**
 * Rows of one file that name another row of the same file through a many-to-one onto their own model
 * — an employee's manager, a department's parent — and what has to happen for them to land.
 *
 * <p>The relation lookup resolves every row against what is already stored, before any row of the file
 * is written, so a manager listed in the same file was "not found" however the rows were ordered. Such a
 * reference is recorded here instead of failing, and the file is written in layers: the rows that name
 * nobody in the file first, then the rows whose target was in the layer before, and so on. Each layer's
 * references are filled from the ids the earlier layers were written under, so a row is inserted with its
 * manager already set rather than patched afterwards.
 */
public final class InFileReferences {

    private InFileReferences() {
    }

    /**
     * One row naming another row of the same file.
     *
     * @param row the row that carries the reference
     * @param rootField the relation field to fill, e.g. {@code piEmployeeId}
     * @param label that field's label, for the reasons a row is refused
     * @param lookupFields the target's fields the reference matches on, e.g. {@code [code]}
     * @param key the referenced values, normalized
     * @param target the row of the file those values name
     */
    public record Reference(Map<String, Object> row, String rootField, String label, List<String> lookupFields,
                            List<Object> key, Map<String, Object> target) {

        String keyText() {
            return key.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
    }

    /**
     * The value a key is compared on: a cell's surrounding spaces are not part of it, and the number a
     * spreadsheet hands over for a numeric code names the same row as its text.
     */
    static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** A row's own values for {@code fields}, or {@code null} when it has none to be named by. */
    static List<Object> ownKey(Map<String, Object> row, List<String> fields) {
        List<Object> key = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object value = normalize(row.get(field));
            if (value == null) {
                return null;
            }
            key.add(value);
        }
        return key;
    }

    /**
     * Refuse the references that cannot be honoured, before anything is written.
     *
     * <p>A row whose target failed would otherwise be written without the manager it names, or fail at
     * the write with a reason about a value the reader can see in the file; it fails here with the
     * actual reason, transitively. Rows that name each other round a loop have no order to be written
     * in and fail together. Runs in the validation-only pipeline too, so a preview reports both.
     *
     * @param skipException false to throw on the first refusal, as a fail-fast import does
     */
    public static void settle(List<Reference> references, boolean skipException) {
        if (references == null || references.isEmpty()) {
            return;
        }
        // Loops first, so the rows naming a row on one are refused by the pass below like any other.
        Map<Map<String, Object>, List<Reference>> outgoing = outgoing(references);
        for (Reference reference : references) {
            if (failed(reference.row())) {
                continue;
            }
            List<Reference> loop = loopFrom(reference, outgoing);
            if (loop != null) {
                String path = loop.stream().map(Reference::keyText).collect(Collectors.joining(" → "));
                String reason = reference.label() + " forms a loop within this file: "
                        + ownKeyText(loop.getFirst()) + " → " + path + ".";
                for (Reference member : loop) {
                    if (!failed(member.row())) {
                        fail(member.row(), reason, skipException);
                    }
                }
            }
        }
        // Fixed point: a failure two hops up reaches a row only once the row in between has failed.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Reference reference : references) {
                if (!failed(reference.row()) && failed(reference.target())) {
                    fail(reference.row(), reference.label() + " " + reference.keyText()
                            + " is a row of this file that was not imported.", skipException);
                    changed = true;
                }
            }
        }
    }

    /**
     * The rows in the order they can be written: layer 0 names nobody in the file, layer n names rows
     * of earlier layers only. Rows keep their file order within a layer. Expects {@link #settle} to
     * have run, so every reference left points at a row that is still in {@code rows} and none loops.
     *
     * @return the indexes into {@code rows}, one list per layer
     */
    public static List<List<Integer>> layers(List<Map<String, Object>> rows, List<Reference> references) {
        Map<Map<String, Object>, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            index.put(rows.get(i), i);
        }
        Map<Map<String, Object>, List<Reference>> outgoing = outgoing(references);
        Map<Map<String, Object>, Integer> depth = new IdentityHashMap<>();
        List<List<Integer>> layers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int d = depthOf(row, outgoing, index, depth);
            while (layers.size() <= d) {
                layers.add(new ArrayList<>());
            }
            layers.get(d).add(index.get(row));
        }
        return layers;
    }

    private static int depthOf(Map<String, Object> row, Map<Map<String, Object>, List<Reference>> outgoing,
                               Map<Map<String, Object>, Integer> index, Map<Map<String, Object>, Integer> depth) {
        Integer known = depth.get(row);
        if (known != null) {
            return known;
        }
        int d = 0;
        for (Reference reference : outgoing.getOrDefault(row, List.of())) {
            if (index.containsKey(reference.target())) {
                d = Math.max(d, depthOf(reference.target(), outgoing, index, depth) + 1);
            }
        }
        depth.put(row, d);
        return d;
    }

    /** The references out of each row; a row may carry several self-referencing columns. */
    private static Map<Map<String, Object>, List<Reference>> outgoing(Collection<Reference> references) {
        Map<Map<String, Object>, List<Reference>> outgoing = new IdentityHashMap<>();
        for (Reference reference : references) {
            outgoing.computeIfAbsent(reference.row(), ignored -> new ArrayList<>()).add(reference);
        }
        return outgoing;
    }

    /** The references round a loop that {@code start} lies on, starting with it, or null when it lies on none. */
    private static List<Reference> loopFrom(Reference start, Map<Map<String, Object>, List<Reference>> outgoing) {
        // Depth-first along the references; a row met again on the current path closes a loop.
        Map<Map<String, Object>, Reference> path = new LinkedHashMap<>();
        return walk(start, path, outgoing, start.row());
    }

    private static List<Reference> walk(Reference step, Map<Map<String, Object>, Reference> path,
                                        Map<Map<String, Object>, List<Reference>> outgoing,
                                        Map<String, Object> origin) {
        path.put(step.row(), step);
        if (step.target() == origin) {
            return new ArrayList<>(path.values());
        }
        if (!path.containsKey(step.target())) {
            for (Reference next : outgoing.getOrDefault(step.target(), List.of())) {
                List<Reference> loop = walk(next, path, outgoing, origin);
                if (loop != null) {
                    return loop;
                }
            }
        }
        path.remove(step.row());
        return null;
    }

    /** How the row a reference starts from is named, by the same fields its target is matched on. */
    private static String ownKeyText(Reference reference) {
        List<Object> key = ownKey(reference.row(), reference.lookupFields());
        return key == null ? "?" : key.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    static boolean failed(Map<String, Object> row) {
        return row.containsKey(FileConstant.FAILED_REASON);
    }

    static void fail(Map<String, Object> row, String reason, boolean skipException) {
        if (!skipException) {
            throw new ValidationException(reason);
        }
        Object existing = row.get(FileConstant.FAILED_REASON);
        row.put(FileConstant.FAILED_REASON, Objects.isNull(existing) ? reason : existing + "; " + reason);
    }
}
