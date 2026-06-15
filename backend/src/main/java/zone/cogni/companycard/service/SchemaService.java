package zone.cogni.companycard.service;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.model.FormField;
import zone.cogni.companycard.model.FormSchema;
import zone.cogni.companycard.model.OntologyClass;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static zone.cogni.companycard.repository.SparqlExecutor.*;

@Service
public class SchemaService {
  private static final String ESCO_NAMESPACE = "http://data.europa.eu/esco/model#";

  private final RdfRepository repository;
  private final OntologyConfig config;

  public SchemaService(RdfRepository repository, OntologyConfig config) {
    this.repository = repository;
    this.config = config;
  }

  @Cacheable("ontologyClasses")
  public List<OntologyClass> getClasses() {
    return fetchAllClasses().stream()
                            .filter(c -> !config.getHiddenClasses()
                                                .contains(UriUtils.extractLocalName(c.uri())))
                            .toList();
  }

  public List<OntologyClass> getAllClasses() {
    return fetchAllClasses();
  }

  private List<OntologyClass> fetchAllClasses() {
    String query = SparqlQueries.findClasses(config.getOntologyNamespace());
    return SparqlExecutor.select(repository.ontology(), query, sol -> {
      String localName = sol.getResource("class").getLocalName();
      return new OntologyClass(
        getUri(sol, "class"),
        getStringOrDefault(sol, "label", localName),
        getStringOrDefault(sol, "comment", "")
      );
    });
  }

  @Cacheable("formSchema")
  public FormSchema getFormSchema(String classUri) {
    String query = SparqlQueries.findFormFieldsFromShapes(classUri);

    List<FormField> fields = SparqlExecutor.select(repository.shapes(), query, sol -> {
      String propUri = getUri(sol, "prop");
      String propLocalName = sol.getResource("prop")
                                .getLocalName();

      String datatypeUri = getUri(sol, "datatype");
      String classRangeUri = getUri(sol, "class");

      boolean isDatatype = datatypeUri != null || classRangeUri == null;
      String rangeUri = datatypeUri != null ? datatypeUri : (classRangeUri != null ? classRangeUri : "");

      int minCount = getInt(sol, "minCount", 0);
      int maxCount = getInt(sol, "maxCount", -1);

      String inverseOf = getInversePropertyUri(propUri);

      boolean createOnly = getBoolean(sol, "createOnly", false);
      boolean noCreate = getBoolean(sol, "noCreate", false);
      boolean computed = getBoolean(sol, "computed", false);
      String conceptScheme = getUri(sol, "conceptScheme");

      return new FormField(
        propUri,
        getStringOrDefault(sol, "name", propLocalName),
        getStringOrDefault(sol, "description", ""),
        rangeUri,
        isDatatype,
        maxCount,
        inverseOf,
        minCount > 0,
        determineConceptSource(propUri, rangeUri, isDatatype, conceptScheme),
        createOnly,
        noCreate,
        computed
      );
    });

    return new FormSchema(classUri, fields);
  }

  public Map<String, String> getPropertyUriMap(String classUri) {
    String query = SparqlQueries.findPropertyUrisFromShapes(classUri);

    return SparqlExecutor.select(repository.shapes(), query, sol -> {
                           String uri = getUri(sol, "prop");
                           String localName = UriUtils.extractLocalName(uri);
                           return Map.entry(localName, uri);
                         })
                         .stream()
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
  }

  @Cacheable("exclusiveProperties")
  public Set<String> getExclusivePropertyUris() {
    return new HashSet<>(SparqlExecutor.select(
      repository.shapes(),
      SparqlQueries.findExclusiveProperties(),
      sol -> getUri(sol, "prop")
    ));
  }

  @Cacheable("identifierProperties")
  public List<String> getIdentifierPropertyLocalNames(String classUri) {
    String query = SparqlQueries.findIdentifierProperties(classUri);

    Model schemaGraph = ModelFactory.createUnion(repository.shapes(), repository.ontology());
    return SparqlExecutor.select(schemaGraph, query,
        sol -> UriUtils.extractLocalName(getUri(sol, "prop")));
  }

  @Cacheable("subPropertyImplications")
  public Map<String, String> getSubPropertyImplications() {
    String query = SparqlQueries.findSubPropertyImplications(config.getOntologyNamespace());
    return SparqlExecutor.select(repository.ontology(), query,
                           sol -> Map.entry(getUri(sol, "sub"), getUri(sol, "super")))
                         .stream()
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
  }

  public Map<String, String> getInverseProperties(String classUri) {
    Map<String, String> result = new HashMap<>();

    result.putAll(collectInverseProperties(SparqlQueries.findDirectInverseProperties(classUri)));

    result.putAll(collectInverseProperties(SparqlQueries.findReverseInverseProperties(classUri)));
    return result;
  }

  private Map<String, String> collectInverseProperties(String query) {
    return SparqlExecutor.select(repository.ontology(), query, sol ->
      Map.entry(
        UriUtils.extractLocalName(getUri(sol, "prop")),
        getUri(sol, "inverseProp")
      )
    ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
  }

  public String getInversePropertyUri(String propertyUri) {
    String query = SparqlQueries.findInverseOfProperty(propertyUri);
    List<String> results = SparqlExecutor.select(repository.ontology(), query, sol -> getUri(sol, "inverse"));
    return results.isEmpty() ? null : results.get(0);
  }

  private String determineConceptSource(String propertyUri, String rangeUri, boolean isDatatype, String conceptScheme) {
    if (isDatatype) return "none";

    if (rangeUri != null && rangeUri.startsWith(ESCO_NAMESPACE)) return "esco";

    if (conceptScheme != null) return "skos";

    return "none";
  }
}
