package io.doindev.cvector.rules;

public record Violation(
        String rule,
        Severity severity,
        String subject,
        String message,
        String fileId,
        Integer line
) {
    public static Violation error(String rule, String subject, String message) {
        return new Violation(rule, Severity.ERROR, subject, message, null, null);
    }

    public static Violation warn(String rule, String subject, String message) {
        return new Violation(rule, Severity.WARN, subject, message, null, null);
    }

    public Violation withLocation(String fileId, Integer line) {
        return new Violation(rule, severity, subject, message, fileId, line);
    }
}
