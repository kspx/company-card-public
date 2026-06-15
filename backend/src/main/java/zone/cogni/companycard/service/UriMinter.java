package zone.cogni.companycard.service;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.model.UriUtils;

import java.util.List;
import java.util.UUID;

@Component
public class UriMinter {
  private final OntologyConfig config;

  public UriMinter(OntologyConfig config) {
    this.config = config;
  }

  public String mint(String classLocalName, List<String> parts, List<String> fallbacks) {
    return config.getBaseDataUri() + classLocalName + "/" + identifier(parts, fallbacks);
  }

  public String mint(String classLocalName, String... parts) {
    return mint(classLocalName, List.of(parts), List.of());
  }

  private String identifier(List<String> parts, List<String> fallbacks) {
    String joined = join(parts);
    if (StringUtils.hasText(joined)) return UriUtils.sanitizeForUri(joined);
    for (String fallback : fallbacks) {
      if (StringUtils.hasText(fallback)) return UriUtils.sanitizeForUri(fallback);
    }
    return UUID.randomUUID().toString();
  }

  private static String join(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (!StringUtils.hasText(part)) continue;
      if (sb.length() > 0) sb.append('-');
      sb.append(part);
    }
    return sb.toString();
  }

  public String ensureUnique(String baseUri, Model model) {
    if (!inUse(baseUri, model)) return baseUri;
    for (int suffix = 2; ; suffix++) {
      String candidate = baseUri + "-" + suffix;
      if (!inUse(candidate, model)) return candidate;
    }
  }

  private static boolean inUse(String uri, Model model) {
    return model.contains(model.createResource(uri), RDF.type, (RDFNode) null);
  }
}
