package zone.cogni.companycard.exception;

public class OntologyException extends RuntimeException {
    public OntologyException(String message) {
        super(message);
    }

    public OntologyException(String message, Throwable cause) {
        super(message, cause);
    }

    public static OntologyException notFound(String entityType, String identifier) {
        return new OntologyException("%s not found: %s".formatted(entityType, identifier));
    }

    public static OntologyException operationFailed(String operation, String target, Throwable cause) {
        return new OntologyException("Failed to %s: %s".formatted(operation, target), cause);
    }

    public static OntologyException validationFailed(String message) {
        return new OntologyException("Validation failed: " + message);
    }
}
