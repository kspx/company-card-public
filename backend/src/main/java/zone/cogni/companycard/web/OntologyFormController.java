package zone.cogni.companycard.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.*;
import zone.cogni.companycard.model.*;
import zone.cogni.companycard.service.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
public class OntologyFormController {
  private final SchemaService schemaService;
  private final InstanceService instanceService;
  private final ValidationService validationService;
  private final ConceptService conceptService;

  public OntologyFormController(SchemaService schemaService, InstanceService instanceService,
                                ValidationService validationService, ConceptService conceptService) {
    this.schemaService = schemaService;
    this.instanceService = instanceService;
    this.validationService = validationService;
    this.conceptService = conceptService;
  }

  record InstanceRequest(
      @JsonProperty("class") String classUri,
      Map<String, Object> values
  ) {}

  record UpdateInstanceRequest(
      String uri,
      @JsonProperty("class") String classUri,
      Map<String, Object> values
  ) {}

  @GetMapping("/classes")
  public List<OntologyClass> getClasses() {
    return schemaService.getClasses();
  }

  @GetMapping("/form-schema")
  public FormSchema getFormSchema(@RequestParam String classUri) {
    return schemaService.getFormSchema(classUri);
  }

  @GetMapping("/instances")
  public List<InstanceReference> getInstances(@RequestParam String classUri) {
    return instanceService.getInstances(classUri);
  }

  @PostMapping("/instance")
  public Map<String, String> createInstance(@RequestBody InstanceRequest req) {
    String uri = instanceService.createInstance(req.classUri(), req.values());
    return Map.of("uri", uri);
  }

  @GetMapping("/instance")
  public Map<String, Object> getInstanceDetails(@RequestParam String uri) {
    return instanceService.getInstanceDetails(uri);
  }

  @PutMapping("/instance")
  public Map<String, String> updateInstance(@RequestBody UpdateInstanceRequest req) {
    instanceService.updateInstance(req.uri(), req.classUri(), req.values());
    return Map.of("uri", req.uri(), "message", "Instance updated successfully");
  }

  @DeleteMapping("/instance")
  public Map<String, String> deleteInstance(@RequestParam String uri) {
    instanceService.deleteInstance(uri);
    return Map.of("message", "Instance deleted successfully");
  }

  @GetMapping("/graph")
  public Map<String, Object> getGraphData() {
    return instanceService.getGraphData();
  }

  @GetMapping("/concepts")
  public List<Concept> getConceptsForProperty(@RequestParam String propertyUri) {
    return conceptService.getConceptsForProperty(propertyUri);
  }

  @PostMapping("/validate")
  public ValidationReport validateInstance(@RequestBody InstanceRequest req) {
    return ValidationReport.from(validationService.validate(req.classUri(), req.values()));
  }

  @GetMapping("/derived/team-membership-start-date")
  public Map<String, String> getDerivedTeamMembershipStartDate(
      @RequestParam String person,
      @RequestParam String team) {
    String startDate = instanceService.deriveTeamMembershipStartDate(person, team);
    Map<String, String> result = new java.util.HashMap<>();
    result.put("startDate", startDate);
    return result;
  }
}
