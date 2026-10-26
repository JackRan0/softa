package io.softa.starter.file.excel.imports;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** The current file's references, while its custom handler runs. */
    private static final ScopedValue<List<Reference>> CURRENT = ScopedValue.newInstance();

    private InFileReferences() {
    }

    /**
     * The row of this file that {@code row} names through {@code rootField}, or {@code null} when it
     * names none — for a custom handler, which runs before the write fills the field in.
     *
     * <p>To a handler such a field reads as empty, the same as a row that names nobody: a department
     * whose parent is listed in the same file looks like a root. Any rule that reads the field has to
     * ask here first, or it judges the row by a value the write has not supplied yet. Only answers
     * while a handler of this pipeline runs; elsewhere there is nothing to ask about.
     */
    public static Map<String, Object> targetOf(Map<String, Object> row, String rootField) {
        if (!CURRENT.isBound()) {
            return null;
        }
        for (Reference reference : CURRENT.get()) {
            if (reference.row() == row && reference.rootField().equals(rootField)) {
                return reference.target();
            }
        }
        return null;
    }

    /**
     * Run a custom handler with the file's references answerable through {@link #targetOf}. The import
     * pipeline wraps every handler in it; public so a handler's own tests can set up the same scope.
     */
    public static void exposedTo(List<Reference> references, Runnable handler) {
        ScopedValue.where(CURRENT, references == null ? List.of() : references).run(handler);
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

    /**
     * How many rows of the file stand above {@code row}. Iterative, with an explicit stack: a chain is
     * as deep as the file makes it, and recursion would turn a long one into a stack overflow that
     * fails the whole import with no row to blame.
     */
    private static int depthOf(Map<String, Object> start, Map<Map<String, Object>, List<Reference>> outgoing,
                               Map<Map<String, Object>, Integer> index, Map<Map<String, Object>, Integer> depth) {
        Deque<Map<String, Object>> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Map<String, Object> row = stack.peek();
            if (depth.containsKey(row)) {
                stack.pop();
                continue;
            }
            int d = 0;
            boolean pending = false;
            for (Reference reference : outgoing.getOrDefault(row, List.of())) {
                Map<String, Object> target = reference.target();
                if (!index.containsKey(target)) {
                    continue;
                }
                Integer known = depth.get(target);
                if (known == null) {
                    // settle() has refused every loop, so a target is never already on the stack.
                    stack.push(target);
                    pending = true;
                } else {
                    d = Math.max(d, known + 1);
                }
            }
            if (!pending) {
                depth.put(row, d);
                stack.pop();
            }
        }
        return depth.get(start);
    }

    /** The references out of each row; a row may carry several self-referencing columns. */
    private static Map<Map<String, Object>, List<Reference>> outgoing(Collection<Reference> references) {
        Map<Map<String, Object>, List<Reference>> outgoing = new IdentityHashMap<>();
        for (Reference reference : references) {
            outgoing.computeIfAbsent(reference.row(), ignored -> new ArrayList<>()).add(reference);
        }
        return outgoing;
    }

    /**
     * The references round a loop that {@code start} lies on, starting with it, or null when it lies on
     * none. Iterative for the same reason as {@link #depthOf}: depth-first along the references, with
     * the path and each row's next reference to try kept on explicit stacks.
     */
    private static List<Reference> loopFrom(Reference start, Map<Map<String, Object>, List<Reference>> outgoing) {
        Map<String, Object> origin = start.row();
        Deque<Reference> path = new ArrayDeque<>();
        Deque<Iterator<Reference>> choices = new ArrayDeque<>();
        Set<Map<String, Object>> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        // Rows from which the origin cannot be reached; each is explored once.
        Set<Map<String, Object>> deadEnds = Collections.newSetFromMap(new IdentityHashMap<>());
        path.push(start);
        onPath.add(origin);
        choices.push(outgoing.getOrDefault(start.target(), List.<Reference>of()).iterator());
        if (start.target() == origin) {
            return List.of(start);
        }
        while (!path.isEmpty()) {
            Iterator<Reference> next = choices.peek();
            if (!next.hasNext()) {
                Reference done = path.pop();
                choices.pop();
                deadEnds.add(done.target());
                onPath.remove(done.row());
                continue;
            }
            Reference step = next.next();
            if (step.target() == origin) {
                List<Reference> loop = new ArrayList<>(path);
                Collections.reverse(loop);
                loop.add(step);
                return loop;
            }
            if (onPath.contains(step.target()) || deadEnds.contains(step.target())) {
                continue;
            }
            path.push(step);
            onPath.add(step.row());
            choices.push(outgoing.getOrDefault(step.target(), List.<Reference>of()).iterator());
        }
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
