package zone.cogni.companycard.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import zone.cogni.companycard.model.Concept;

import java.util.ArrayList;
import java.util.List;

@Service
public class EscoService {
  private static final Logger log = LoggerFactory.getLogger(EscoService.class);

  private final RestTemplate restTemplate;
  private final String escoApiBaseUrl;

  public EscoService(
    RestTemplate restTemplate,
    @Value("${esco.api.base-url:https://ec.europa.eu/esco/api}") String escoApiBaseUrl) {
    this.restTemplate = restTemplate;
    this.escoApiBaseUrl = escoApiBaseUrl;
  }

  @Cacheable(value = "escoSkills", key = "#text + '_' + #language + '_' + #limit")
  public List<Concept> searchSkills(String text, String language, int limit) {
    if (!StringUtils.hasText(text)) {
      return List.of();
    }

    String url = UriComponentsBuilder.fromHttpUrl(escoApiBaseUrl + "/search")
                                     .queryParam("text", text)
                                     .queryParam("type", "skill")
                                     .queryParam("language", language)
                                     .queryParam("limit", limit)
                                     .toUriString();

    try {
      JsonNode response = restTemplate.getForObject(url, JsonNode.class);
      return parseSearchResults(response);
    }
    catch (Exception e) {
      log.warn("ESCO API call failed for query '{}': {}", text, e.getMessage());
      return List.of();
    }
  }

  @Cacheable(value = "escoOccupations", key = "#text + '_' + #language + '_' + #limit")
  public List<Concept> searchOccupations(String text, String language, int limit) {
    if (!StringUtils.hasText(text)) {
      return List.of();
    }

    String url = UriComponentsBuilder.fromHttpUrl(escoApiBaseUrl + "/search")
                                     .queryParam("text", text)
                                     .queryParam("type", "occupation")
                                     .queryParam("language", language)
                                     .queryParam("limit", limit)
                                     .toUriString();

    try {
      JsonNode response = restTemplate.getForObject(url, JsonNode.class);
      return parseSearchResults(response);
    }
    catch (Exception e) {
      log.warn("ESCO API call failed for occupation query '{}': {}", text, e.getMessage());
      return List.of();
    }
  }

  @Cacheable(value = "escoOccupationDetails", key = "#uri + '_' + #language")
  public Concept getOccupationDetails(String uri, String language) {
    String url = UriComponentsBuilder.fromHttpUrl(escoApiBaseUrl + "/resource/occupation")
                                     .queryParam("uri", uri)
                                     .queryParam("language", language)
                                     .toUriString();

    try {
      JsonNode response = restTemplate.getForObject(url, JsonNode.class);
      String title = response != null ? response.path("title")
                                                .asText(null) : null;
      return Concept.of(uri, title != null ? title : uri);
    }
    catch (Exception e) {
      log.warn("ESCO API occupation detail call failed for '{}': {}", uri, e.getMessage());
      return Concept.of(uri, uri);
    }
  }

  @Cacheable(value = "escoSkillDetails", key = "#uri + '_' + #language")
  public Concept getSkillDetails(String uri, String language) {
    String url = UriComponentsBuilder.fromHttpUrl(escoApiBaseUrl + "/resource/skill")
                                     .queryParam("uri", uri)
                                     .queryParam("language", language)
                                     .toUriString();

    try {
      JsonNode response = restTemplate.getForObject(url, JsonNode.class);
      String title = response != null ? response.path("title")
                                                .asText(null) : null;
      return Concept.of(uri, title != null ? title : uri);
    }
    catch (Exception e) {
      log.warn("ESCO API skill detail call failed for '{}': {}", uri, e.getMessage());
      return Concept.of(uri, uri);
    }
  }

  private List<Concept> parseSearchResults(JsonNode response) {
    List<Concept> results = new ArrayList<>();
    if (response == null) return results;

    JsonNode embedded = response.path("_embedded");
    JsonNode resultArray = embedded.path("results");

    if (resultArray.isArray()) {
      for (JsonNode item : resultArray) {
        String uri = item.path("uri")
                         .asText(null);
        String title = item.path("title")
                           .asText(null);
        if (uri != null && title != null) {
          results.add(Concept.of(uri, title));
        }
      }
    }

    return results;
  }
}
