package zone.cogni.companycard.model;

public record FormField(
        String property,
        String label,
        String comment,
        String range,
        boolean isDatatypeProperty,
        int maxCount,
        String inverseOf,
        boolean required,
        String conceptSource,
        boolean createOnly,
        boolean noCreate,
        boolean computed
) {
}
