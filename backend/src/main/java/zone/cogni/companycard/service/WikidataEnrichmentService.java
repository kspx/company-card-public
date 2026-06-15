package zone.cogni.companycard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WikidataEnrichmentService {
  private static final Logger log = LoggerFactory.getLogger(WikidataEnrichmentService.class);

  private static final String ENDPOINT = "https://query.wikidata.org/sparql";
  private static final String SEARCH_ENDPOINT = "https://www.wikidata.org/w/api.php";
  private static final String ENTITY_PREFIX = "https://www.wikidata.org/wiki/";
  private static final String USER_AGENT = "CompanyCardThesis/1.0 (https://cogni.zone)";
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  public record Candidate(String qid, String uri, String label, String description) {}

  public record Headquarters(String city, String countryIso3) {}

  private static final String HQ_SPARQL = """
    PREFIX wd:   <http://www.wikidata.org/entity/>
    PREFIX wdt:  <http://www.wikidata.org/prop/direct/>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    SELECT ?city ?iso3 WHERE {
      wd:%1$s wdt:P159 ?hq .
      ?hq rdfs:label ?city FILTER(LANG(?city) = "en")
      OPTIONAL { ?hq wdt:P17 ?country . ?country wdt:P298 ?iso3 }
    }
    LIMIT 1
    """;

  private static final String SPARQL = """
    PREFIX wd:     <http://www.wikidata.org/entity/>
    PREFIX wdt:    <http://www.wikidata.org/prop/direct/>
    PREFIX p:      <http://www.wikidata.org/prop/>
    PREFIX ps:     <http://www.wikidata.org/prop/statement/>
    PREFIX pq:     <http://www.wikidata.org/prop/qualifier/>
    PREFIX rdfs:   <http://www.w3.org/2000/01/rdf-schema#>
    PREFIX skos:   <http://www.w3.org/2004/02/skos/core#>
    PREFIX schema: <http://schema.org/>
    SELECT ?legalName ?foundingDate ?website ?employees ?lei ?description ?altLabel WHERE {
      OPTIONAL { wd:%1$s rdfs:label ?legalName FILTER(LANG(?legalName) = "en") }
      OPTIONAL { wd:%1$s wdt:P571 ?foundingDate }
      OPTIONAL { wd:%1$s wdt:P856 ?website }
      OPTIONAL {
        wd:%1$s wdt:P1128 ?employees
        FILTER NOT EXISTS {
          wd:%1$s p:P1128/pq:P585 ?anyDate
        }
      }
      OPTIONAL {
        SELECT ?employees WHERE {
          wd:%1$s p:P1128 [ ps:P1128 ?employees ; pq:P585 ?empDate ]
        }
        ORDER BY DESC(?empDate)
        LIMIT 1
      }
      OPTIONAL { wd:%1$s wdt:P1278 ?lei }
      OPTIONAL { wd:%1$s schema:description ?description FILTER(LANG(?description) = "en") }
      {
        SELECT (GROUP_CONCAT(DISTINCT ?al; SEPARATOR=" / ") AS ?altLabel) WHERE {
          wd:%1$s skos:altLabel ?al FILTER(LANG(?al) = "en")
        }
      }
    }
    LIMIT 1
    """;

  private final HttpClient http = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(TIMEOUT)
    .build();

  private final ObjectMapper objectMapper;

  public WikidataEnrichmentService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  private static final java.util.Set<String> NO_REGISTRATION_QIDS = java.util.Set.of(
    "Q8880",
    "Q8889",
    "Q8868",
    "Q7188"
  );

  public Map<String, Object> fetch(String wikidataUri) {
    String qid = extractQid(wikidataUri);
    if (qid == null) return Map.of();
    try {
      String body = query(SPARQL.formatted(qid));
      Map<String, Object> data = parseFirstBinding(body, NO_REGISTRATION_QIDS.contains(qid));
      if (!data.isEmpty()) {
        data.put("sameAs", ENTITY_PREFIX + qid);
        inferCompanySize(data);
      }
      return data;
    }
    catch (Exception e) {
      log.warn("Wikidata fetch failed for {}: {}", qid, e.getMessage());
      return Map.of();
    }
  }

  public Headquarters fetchHeadquarters(String wikidataUri) {
    String qid = extractQid(wikidataUri);
    if (qid == null) return null;
    try {
      String body = query(HQ_SPARQL.formatted(qid));
      JsonNode bindings = objectMapper.readTree(body).path("results").path("bindings");
      if (!bindings.isArray() || bindings.isEmpty()) return null;
      JsonNode row = bindings.get(0);
      String city = value(row, "city");
      String iso3 = value(row, "iso3");
      if (city == null || iso3 == null) return null;
      return new Headquarters(city, iso3);
    }
    catch (Exception e) {
      log.warn("Wikidata HQ fetch failed for {}: {}", wikidataUri, e.getMessage());
      return null;
    }
  }

  public List<Candidate> search(String text, int limit) {
    if (text == null || text.isBlank()) return List.of();
    try {
      String url = SEARCH_ENDPOINT
        + "?action=wbsearchentities"
        + "&format=json"
        + "&language=en"
        + "&type=item"
        + "&limit=" + Math.max(1, Math.min(limit, 20))
        + "&search=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
      return parseSearchResults(httpGet(url, "application/json"));
    }
    catch (Exception e) {
      log.warn("Wikidata search failed for '{}': {}", text, e.getMessage());
      return List.of();
    }
  }

  private List<Candidate> parseSearchResults(String json) throws Exception {
    JsonNode arr = objectMapper.readTree(json).path("search");
    if (!arr.isArray()) return List.of();
    List<Candidate> out = new ArrayList<>();
    for (JsonNode node : arr) {
      String qid = node.path("id").asText(null);
      if (qid == null) continue;
      String label = node.path("label").asText("");
      String description = node.path("description").asText("");
      out.add(new Candidate(qid, ENTITY_PREFIX + qid, label, description));
    }
    return out;
  }

  private static void inferCompanySize(Map<String, Object> data) {
    Object raw = data.get("numberOfEmployees");
    if (!(raw instanceof Number n)) return;
    int count = n.intValue();
    String concept = count < 10  ? "https://ontology.cogni.zone/company-card-vocabularies#MICRO"
                   : count < 50  ? "https://ontology.cogni.zone/company-card-vocabularies#SMALL"
                   : count < 250 ? "https://ontology.cogni.zone/company-card-vocabularies#MEDIUM"
                                 : "https://ontology.cogni.zone/company-card-vocabularies#LARGE";
    data.put("companySize", concept);
  }

  private String query(String sparql) throws Exception {
    String url = ENDPOINT + "?query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
    return httpGet(url, "application/sparql-results+json");
  }

  private String httpGet(String url, String accept) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Accept", accept)
      .header("User-Agent", USER_AGENT)
      .timeout(TIMEOUT)
      .GET()
      .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Wikidata status " + response.statusCode());
    }
    return response.body();
  }

  private Map<String, Object> parseFirstBinding(String json, boolean skipRegistration) throws Exception {
    JsonNode bindings = objectMapper.readTree(json).path("results").path("bindings");
    if (!bindings.isArray() || bindings.isEmpty()) return Map.of();
    JsonNode row = bindings.get(0);

    Map<String, Object> data = new LinkedHashMap<>();
    putString(data, "legalName", row, "legalName");
    putString(data, "altLabel", row, "altLabel");
    putString(data, "website", row, "website");
    putString(data, "description", row, "description");
    putDate(data, "foundingDate", row, "foundingDate");
    putInt(data, "numberOfEmployees", row, "employees");
    if (!skipRegistration) putString(data, "lei", row, "lei");
    return data;
  }

  private static void putString(Map<String, Object> data, String key, JsonNode row, String var) {
    String v = value(row, var);
    if (v != null && !v.isEmpty()) data.put(key, v);
  }

  private static void putDate(Map<String, Object> data, String key, JsonNode row, String var) {
    String v = value(row, var);
    if (v == null) return;
    int t = v.indexOf('T');
    data.put(key, t > 0 ? v.substring(0, t) : v);
  }

  private static void putInt(Map<String, Object> data, String key, JsonNode row, String var) {
    String v = value(row, var);
    if (v == null) return;
    try { data.put(key, Integer.parseInt(v)); }
    catch (NumberFormatException ignored) {}
  }

  private static String value(JsonNode row, String var) {
    JsonNode node = row.path(var).path("value");
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }

  private static String extractQid(String uri) {
    if (uri == null || uri.isBlank()) return null;
    if (uri.matches("Q\\d+")) return uri;
    if (!uri.contains("wikidata.org")) return null;
    int idx = uri.lastIndexOf('/');
    return idx > 0 && idx < uri.length() - 1 ? uri.substring(idx + 1) : null;
  }
}
