package zone.cogni.companycard.model;

import java.util.List;

public record ValidationResult(
        boolean conforms,
        List<Violation> violations
) {
    public static ValidationResult valid() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult invalid(List<Violation> violations) {
        return new ValidationResult(false, violations);
    }

    public record Violation(
            String property,
            String message,
            String severity
    ) {
        public static Violation required(String propertyUri, String message) {
            return new Violation(propertyUri, message, "Violation");
        }
    }
}
