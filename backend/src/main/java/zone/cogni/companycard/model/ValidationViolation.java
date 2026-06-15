package zone.cogni.companycard.model;

public record ValidationViolation(
  String property,
  String message,
  String severity
) {
  public static ValidationViolation from(ValidationResult.Violation v) {
    return new ValidationViolation(v.property(), v.message(), v.severity());
  }
}
