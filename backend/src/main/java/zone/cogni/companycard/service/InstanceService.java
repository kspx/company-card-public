package zone.cogni.companycard.service;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.exception.OntologyException;
import zone.cogni.companycard.model.InstanceReference;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.model.ValidationResult;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static zone.cogni.companycard.repository.SparqlExecutor.*;

@Service
public class InstanceService {
  private static final Map<String, String> FACET_PROPERTY = Map.of(
    Namespaces.CC + "Project", Namespaces.CC + "projectStatus",
    Namespaces.CC + "Person", Namespaces.CC + "availabilityStatus",
    Namespaces.CC + "Engagement", Namespaces.CC + "engagementStatus",
    Namespaces.CC + "Team", Namespaces.CC + "teamType",
    Namespaces.CC + "Organization", Namespaces.CC + "companySize");

  private final RdfRepository repository;
  private final SchemaService schemaService;
  private final ValidationService validationService;
  private final OntologyConfig config;
  private final InstanceRdfMapper rdfMapper;
  private final LabelResolver labelResolver;
  private final UriMinter uriMinter;

  public InstanceService(RdfRepository repository, SchemaService schemaService,
                         ValidationService validationService, OntologyConfig config,
                         InstanceRdfMapper rdfMapper, LabelResolver labelResolver,
                         UriMinter uriMinter) {
    this.repository = repository;
    this.schemaService = schemaService;
    this.validationService = validationService;
    this.config = config;
    this.rdfMapper = rdfMapper;
    this.labelResolver = labelResolver;
    this.uriMinter = uriMinter;
  }

  public List<InstanceReference> getInstances(String classUri) {
    return getInstances(classUri, new HashMap<>());
  }

  private List<InstanceReference> getInstances(String classUri, Map<String, String> labelCache) {
    List<String> identPropNames = schemaService.getIdentifierPropertyLocalNames(classUri);
    Map<String, String> propUriMap = identPropNames.isEmpty() ? Map.of() : schemaService.getPropertyUriMap(classUri);

    prewarmEscoLabels(classUri, identPropNames, propUriMap, labelCache);

    String facetProp = FACET_PROPERTY.get(classUri);

    return repository.read(model -> {
      Resource classResource = model.createResource(classUri);
      return model.listSubjectsWithProperty(RDF.type, classResource)
                  .mapWith(instance -> {
                    String label = labelResolver.buildInstanceLabel(instance, identPropNames, propUriMap, model, labelCache);
                    return InstanceReference.of(instance.getURI(), label, facetStatus(facetProp, instance, model));
                  })
                  .toList();
    });
  }

  private String facetStatus(String facetProp, Resource instance, Model model) {
    if (facetProp == null) return null;
    Resource concept = instance.getPropertyResourceValue(model.createProperty(facetProp));
    return concept == null ? null : repository.getVocabularyLabel(concept.getURI());
  }

  private void prewarmEscoLabels(String classUri, List<String> identPropNames,
                                 Map<String, String> propUriMap, Map<String, String> labelCache) {
    List<String> escoIdentProps = schemaService.getFormSchema(classUri)
      .fields()
      .stream()
      .filter(f -> !f.isDatatypeProperty() && labelResolver.isEscoUri(f.range()))
      .map(f -> UriUtils.extractLocalName(f.property()))
      .filter(identPropNames::contains)
      .toList();
    if (escoIdentProps.isEmpty()) return;

    Set<String> escoUris = repository.read(model -> {
      Resource classResource = model.createResource(classUri);
      Set<String> uris = new HashSet<>();
      model.listSubjectsWithProperty(RDF.type, classResource).forEachRemaining(instance ->
        escoIdentProps.forEach(propName -> {
          String propUri = propUriMap.getOrDefault(propName, config.getOntologyNamespace() + propName);
          Resource ref = instance.getPropertyResourceValue(model.createProperty(propUri));
          if (ref != null && labelResolver.isEscoUri(ref.getURI())) uris.add(ref.getURI());
        }));
      return uris;
    });

    escoUris.forEach(uri -> labelCache.computeIfAbsent(uri, labelResolver::resolveEscoLabel));
  }

  public Map<String, Object> getInstanceDetails(String instanceUri) {
    return repository.read(model -> {
      Resource instance = model.getResource(instanceUri);
      if (!model.containsResource(instance)) {
        throw OntologyException.notFound("Instance", instanceUri);
      }

      Map<String, Object> result = new HashMap<>();
      collectDirectProperties(instance, result);
      collectInversePropertyValues(instance, model, result);
      result.put("uri", instanceUri);
      return result;
    });
  }

  public String deriveTeamMembershipStartDate(String personUri, String teamUri) {
    return repository.read(model -> {
      List<String> dates = SparqlExecutor.select(model,
        SparqlQueries.findEarliestContributionStartDateForTeamMember(personUri, teamUri),
        row -> SparqlExecutor.getString(row, "startDate"));
      return dates.isEmpty() ? null : dates.get(0);
    });
  }

  public Map<String, Object> getGraphData() {
    Map<String, String> labelCache = new HashMap<>();
    List<Map<String, String>> nodes = schemaService.getAllClasses()
      .stream()
      .flatMap(cls -> getInstances(cls.uri(), labelCache).stream()
        .map(inst -> Map.of(
          "id", inst.uri(),
          "label", inst.label() != null ? inst.label() : inst.uri(),
          "type", cls.uri(),
          "typeLabel", cls.label()
        ))
      )
      .toList();
    return Map.of("nodes", nodes, "edges", getGraphEdges());
  }

  public List<Map<String, String>> getGraphEdges() {
    return repository.read(model ->
      SparqlExecutor.select(model, SparqlQueries.findGraphEdges(config.getOntologyNamespace()), sol -> {
        String subject = getUri(sol, "subject");
        String predicate = getUri(sol, "predicate");
        String object = getUri(sol, "object");
        if (subject == null || predicate == null || object == null) return null;
        return Map.of("source", subject, "target", object, "label", UriUtils.extractLocalName(predicate));
      })
    );
  }

  public String createInstance(String classUri, Map<String, Object> values) {
    validateBeforeWrite(classUri, values);
    String mintedUri = mintUri(classUri, values);
    String[] instanceUri = {mintedUri};

    repository.write(model -> {
      instanceUri[0] = uriMinter.ensureUnique(mintedUri, model);
      Model staging = ModelFactory.createDefaultModel();
      rdfMapper.writeInstance(instanceUri[0], classUri, values, staging, model);
      model.add(staging);
      model.add(model.createResource(instanceUri[0]), model.createProperty(Namespaces.DCT + "source"), model.createResource(Namespaces.CCV_MANUAL));
    });

    return instanceUri[0];
  }

  public void updateInstance(String instanceUri, String classUri, Map<String, Object> values) {
    validateBeforeWrite(classUri, values);
    Set<String> managedProperties = managedPropertyUris(classUri);

    repository.write(model -> {
      Resource instance = model.getResource(instanceUri);
      if (!model.containsResource(instance)) {
        throw OntologyException.notFound("Instance", instanceUri);
      }
      model.remove(rdfMapper.collectTriplesForRemoval(instance, model, managedProperties));
      rdfMapper.writeInstance(instanceUri, classUri, values, model, model);
    });
  }

  private Set<String> managedPropertyUris(String classUri) {
    Set<String> managed = new HashSet<>(schemaService.getPropertyUriMap(classUri).values());
    managed.addAll(schemaService.getSubPropertyImplications().values());
    return managed;
  }

  public void deleteInstance(String instanceUri) {
    repository.write(model -> {
      Resource instance = model.getResource(instanceUri);
      if (!model.containsResource(instance)) {
        throw OntologyException.notFound("Instance", instanceUri);
      }
      model.remove(rdfMapper.collectInverseTriplesForRemoval(instance, model));
      instance.removeProperties();
    });
  }

  private String mintUri(String classUri, Map<String, Object> values) {
    List<String> parts = schemaService.getIdentifierPropertyLocalNames(classUri)
                                      .stream()
                                      .map(prop -> toIdentifierPart(values.get(prop)))
                                      .filter(Objects::nonNull)
                                      .toList();

    Object githubUsername = values.get("githubUsername");
    List<String> fallbacks = githubUsername == null ? List.of() : List.of(githubUsername.toString());
    return uriMinter.mint(UriUtils.extractLocalName(classUri), parts, fallbacks);
  }

  private String toIdentifierPart(Object value) {
    if (value == null) return null;
    String s = value.toString();
    if (s.isBlank()) return null;
    return UriUtils.isUri(s) ? UriUtils.extractLocalName(s) : s;
  }

  private void validateBeforeWrite(String classUri, Map<String, Object> values) {
    ValidationResult result = validationService.validate(classUri, values);
    if (!result.conforms()) {
      String errors = result.violations()
                            .stream()
                            .map(v -> v.property() + ": " + v.message())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("Unknown validation error");
      throw OntologyException.validationFailed(errors);
    }
  }

  private void collectDirectProperties(Resource instance, Map<String, Object> result) {
    instance.listProperties()
            .filterDrop(stmt -> RDF.type.equals(stmt.getPredicate()))
            .forEachRemaining(stmt -> {
              Object value = SparqlExecutor.getValue(stmt.getObject());
              if (value != null) {
                mergeIntoResult(result, stmt.getPredicate().getLocalName(), value);
              }
            });
  }

  @SuppressWarnings("unchecked")
  private void mergeIntoResult(Map<String, Object> result, String key, Object value) {
    result.merge(key, value, (existing, newVal) -> {
      if (existing instanceof List<?> list) {
        ((List<Object>) list).add(newVal);
        return existing;
      }
      return new ArrayList<>(List.of(existing, newVal));
    });
  }

  private void collectInversePropertyValues(Resource instance, Model model, Map<String, Object> result) {
    getClassUri(instance).ifPresent(classUri ->
      schemaService.getInverseProperties(classUri).forEach((propName, inversePropUri) -> {
        if (result.containsKey(propName)) return;
        List<String> values = model
          .listStatements(null, model.createProperty(inversePropUri), instance)
          .mapWith(stmt -> stmt.getSubject().getURI())
          .toList();
        if (!values.isEmpty()) {
          result.put(propName, values.size() == 1 ? values.get(0) : values);
        }
      })
    );
  }

  private Optional<String> getClassUri(Resource instance) {
    return Optional.ofNullable(instance.getPropertyResourceValue(RDF.type))
                   .map(Resource::getURI);
  }
}
