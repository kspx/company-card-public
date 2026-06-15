package zone.cogni.companycard.service;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class LabelResolver {
  private static final String ESCO_URI_PREFIX = "http://data.europa.eu/esco/";

  private final OntologyConfig config;
  private final EscoService escoService;

  public LabelResolver(OntologyConfig config, EscoService escoService) {
    this.config = config;
    this.escoService = escoService;
  }

  public String buildInstanceLabel(Resource instance, List<String> identPropNames,
                                   Map<String, String> propUriMap, Model model) {
    return buildInstanceLabel(instance, identPropNames, propUriMap, model, new HashMap<>());
  }

  public String buildInstanceLabel(Resource instance, List<String> identPropNames,
                                   Map<String, String> propUriMap, Model model,
                                   Map<String, String> labelCache) {
    return identPropNames.stream()
                         .map(propName -> {
                           String propUri = propUriMap.getOrDefault(propName, config.getOntologyNamespace() + propName);
                           Statement stmt = instance.getProperty(model.createProperty(propUri));
                           if (stmt == null) return null;
                           RDFNode obj = stmt.getObject();
                           if (obj.isLiteral()) return obj.asLiteral().getString();
                           if (obj.isURIResource()) return resolveEntityLabel(obj.asResource(), model, labelCache);
                           return null;
                         })
                         .filter(StringUtils::hasText)
                         .collect(Collectors.joining(" · "));
  }

  public String resolveEntityLabel(Resource target, Model model) {
    return resolveEntityLabel(target, model, new HashMap<>());
  }

  public String resolveEscoLabel(String uri) {
    if (uri == null || !uri.startsWith(ESCO_URI_PREFIX)) return null;
    String label = uri.contains("/skill/")
      ? escoService.getSkillDetails(uri, "en").label()
      : escoService.getOccupationDetails(uri, "en").label();
    return (label != null && !label.equals(uri)) ? label : UriUtils.extractLocalName(uri);
  }

  public boolean isEscoUri(String uri) {
    return uri != null && uri.startsWith(ESCO_URI_PREFIX);
  }

  public String resolveEntityLabel(Resource target, Model model, Map<String, String> labelCache) {
    String uri = target.getURI();
    if (uri != null) {
      String cached = labelCache.get(uri);
      if (cached != null) return cached;
    }
    String label = computeEntityLabel(target, model);
    if (uri != null && label != null) labelCache.put(uri, label);
    return label;
  }

  private String computeEntityLabel(Resource target, Model model) {
    String ccNs = config.getOntologyNamespace();
    for (String uri : List.of(
      Namespaces.SCHEMA + "name",
      Namespaces.ROV + "legalName",
      Namespaces.DOAP + "name",
      ccNs + "teamName",
      ccNs + "certificationName",
      "http://www.w3.org/2000/01/rdf-schema#label",
      "http://www.w3.org/2004/02/skos/core#prefLabel",
      "http://www.w3.org/2004/02/skos/core#altLabel"
    )) {
      Statement stmt = target.getProperty(model.createProperty(uri));
      if (stmt != null) return stmt.getObject().asLiteral().getString();
    }
    Statement given = target.getProperty(model.createProperty(Namespaces.SCHEMA + "givenName"));
    if (given != null) {
      String name = given.getString();
      Statement family = target.getProperty(model.createProperty(Namespaces.SCHEMA + "familyName"));
      if (family != null) name += " " + family.getString();
      return name;
    }
    Optional<String> githubUsername = AdmsIdentifiers.notation(target, Namespaces.CCV_GITHUB);
    if (githubUsername.isPresent()) return UriUtils.humanize(githubUsername.get());
    String targetUri = target.getURI();
    if (targetUri != null && targetUri.startsWith(ESCO_URI_PREFIX)) {
      String label = targetUri.contains("/skill/")
        ? escoService.getSkillDetails(targetUri, "en").label()
        : escoService.getOccupationDetails(targetUri, "en").label();
      if (label != null && !label.equals(targetUri)) return label;
    }
    return UriUtils.humanize(UriUtils.extractLocalName(targetUri));
  }
}
