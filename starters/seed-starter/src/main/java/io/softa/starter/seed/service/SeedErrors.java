package io.softa.starter.seed.service;

/** How seed data operations word a failure for whoever reads the record. */
final class SeedErrors {

    /** Error messages are kept to a size a record shows in full. */
    static final int LIMIT = 4000;

    private SeedErrors() {
    }

    /** The exception's message, with its root cause's when that says something else — the database's own words. */
    static String messageOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String message = root == e || e.getMessage() == null || e.getMessage().contains(rootMessage)
                ? rootMessage : e.getMessage() + " (" + rootMessage + ")";
        return message.length() <= LIMIT ? message : message.substring(0, LIMIT);
    }
}
