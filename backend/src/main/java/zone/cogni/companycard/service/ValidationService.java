package zone.cogni.companycard.service;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.query.QuerySolution;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.model.ValidationResult;
import zone.cogni.companycard.model.ValidationResult.Violation;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static zone.cogni.companycard.repository.SparqlExecutor.*;

@Service
public class ValidationService {
  private final RdfRepository repository;

  public ValidationService(RdfRepository repository) {
    this.repository = repository;
  }

  public ValidationResult validate(String classUri, Map<String, Object> values) {
    List<Violation> violations = SparqlExecutor.select(
      repository.shapes(),
      SparqlQueries.findPropertyConstraints(classUri),
      sol -> checkConstraint(sol, values)
    );
    return violations.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(violations);
  }

  private Violation checkConstraint(QuerySolution sol, Map<String, Object> values) {
    String propUri = getUri(sol, "prop");
    String propName = UriUtils.extractLocalName(propUri);
    Object value = values.get(propName);
    int minCount = getInt(sol, "minCount", 0);
    int maxCount = getInt(sol, "maxCount", -1);
    String datatype = getUri(sol, "datatype");
    String pattern = getStringOrDefault(sol, "pattern", null);

    if (minCount > 0 && isValueBlank(value)) {
      return Violation.required(propUri, getStringOrDefault(sol, "message", "Property " + propName + " is required"));
    }

    if (isValueBlank(value)) return null;

    if (maxCount >= 0 && countValues(value) > maxCount) {
      return new Violation(propUri, getStringOrDefault(sol, "message",
        propName + " allows at most " + maxCount + " value(s)"), "Violation");
    }

    if (datatype != null) {
      Violation datatypeViolation = checkDatatype(propUri, propName, value, datatype, sol);
      if (datatypeViolation != null) return datatypeViolation;
    }

    if (pattern != null) {
      Pattern compiled = Pattern.compile(pattern);
      return toStringList(value).stream()
                                .filter(v -> !compiled.matcher(v)
                                                      .find())
                                .findFirst()
                                .map(v -> new Violation(propUri,
                                  getStringOrDefault(sol, "message", propName + " does not match required pattern")
                                  + " (value: " + v + ")", "Violation"))
                                .orElse(null);
    }

    return null;
  }

  private Violation checkDatatype(String propUri, String propName, Object value, String datatypeUri, QuerySolution sol) {
    RDFDatatype dtype = TypeMapper.getInstance().getTypeByName(datatypeUri);
    if (dtype == null) return null;
    return toStringList(value).stream()
                              .filter(v -> !dtype.isValid(v))
                              .findFirst()
                              .map(v -> new Violation(propUri,
                                getStringOrDefault(sol, "message",
                                  propName + " must be a valid " + UriUtils.extractLocalName(datatypeUri))
                                + " (value: " + v + ")", "Violation"))
                              .orElse(null);
  }

  private int countValues(Object value) {
    if (value instanceof Collection<?> c) {
      return (int) c.stream().filter(item -> !(item == null || (item instanceof String s && s.isBlank()))).count();
    }
    return 1;
  }

  private boolean isValueBlank(Object value) {
    if (value == null) return true;
    if (value instanceof String s) return s.isBlank();
    if (value instanceof Collection<?> c) {
      return c.isEmpty() || c.stream()
                             .allMatch(item -> item == null || (item instanceof String s && s.isBlank()));
    }
    return false;
  }

  private List<String> toStringList(Object value) {
    if (value instanceof String s) return List.of(s);
    if (value instanceof Collection<?> c) return c.stream()
                                                  .map(Object::toString)
                                                  .toList();
    return List.of(value.toString());
  }
}
