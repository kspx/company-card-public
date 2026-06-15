package zone.cogni.companycard.model;

import java.util.List;

public record ValidationReport(
  boolean conforms,
  List<ValidationViolation> violations
) {
  public static ValidationReport from(ValidationResult result) {
    List<ValidationViolation> violations = result.violations()
      .stream()
      .map(ValidationViolation::from)
      .toList();
    return new ValidationReport(result.conforms(), violations);
  }
}
