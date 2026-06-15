package zone.cogni.companycard.service;

import be.ugent.rml.Executor;
import be.ugent.rml.records.RecordsFactory;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.store.QuadStoreFactory;
import be.ugent.rml.store.RDF4JStore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.repository.RdfRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static zone.cogni.companycard.repository.ModelWrite.addIfMissing;
import static zone.cogni.companycard.repository.ModelWrite.addIfMissingUriLiteral;
import static zone.cogni.companycard.repository.ModelWrite.setBoolean;
import static zone.cogni.companycard.repository.ModelWrite.setDate;
import static zone.cogni.companycard.repository.ModelWrite.setInt;
import static zone.cogni.companycard.repository.ModelWrite.setLiterals;
import static zone.cogni.companycard.repository.ModelWrite.stampNow;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class GitHubRmlService {
  private static final Logger log = LoggerFactory.getLogger(GitHubRmlService.class);

  public static final String DEFAULT_CONTRIBUTION_ROLE = Namespaces.CCV + "DEVELOPER";

  private static final String SEED_CONFIG          = "/seed/known-organizations.json";
  private static final String PERSON_OVERRIDES     = "/seed/person-overrides.json";
  private static final String MAPPING_PERSON       = "/mappings/github-person.rml.ttl";
  private static final String MAPPING_PROJECT      = "/mappings/github-project.rml.ttl";
  private static final String MAPPING_CONTRIBUTION = "/mappings/github-contribution.rml.ttl";
  private static final String MAPPING_SEED_ORG     = "/mappings/seed-organization.rml.ttl";

  public record ContributionData(
    String contributionId,
    String personUri,
    String projectUri,
    String contributionRole,
    int commitCount,
    int prReviewCount,
    int mergedPrCount,
    String startDate,
    String lastCommitDate,
    int linesAdded,
    int linesDeleted
  ) {}

  private final ObjectMapper objectMapper;
  private final RdfRepository rdfRepository;
  private final WikidataEnrichmentService wikidata;

  public GitHubRmlService(ObjectMapper objectMapper, RdfRepository rdfRepository,
                          WikidataEnrichmentService wikidata) {
    this.objectMapper = objectMapper;
    this.rdfRepository = rdfRepository;
    this.wikidata = wikidata;
  }

  public String importPerson(GitHubClient.UserProfile profile) {
    try {
      Model rmlOutput = executeRml("github-user.json", MAPPING_PERSON, profile);
      Resource subject = findUriSubjectOfType(rmlOutput, Namespaces.CC + "Person");
      String personUri = subject != null ? subject.getURI() : null;

      rdfRepository.write(store -> {
        upsertModel(store, rmlOutput);
        if (personUri != null) {
          AdmsIdentifiers.set(store, store.createResource(personUri), Namespaces.CCV_GITHUB, profile.login(), githubUrl(profile.login()));
          stampNow(store, store.createResource(personUri));
        }
      });
      return personUri;
    }
    catch (Exception e) {
      log.error("RML person import failed for {}: {}", profile.login(), e.getMessage(), e);
      return null;
    }
  }

  public void attachGithubIdentity(String personUri, GitHubClient.UserProfile profile) {
    rdfRepository.write(store -> {
      Resource res = store.createResource(personUri);
      AdmsIdentifiers.set(store, res, Namespaces.CCV_GITHUB, profile.login(), githubUrl(profile.login()));
      addIfMissing(store, res, Namespaces.SCHEMA + "givenName", profile.givenName());
      addIfMissing(store, res, Namespaces.SCHEMA + "familyName", profile.familyName());
      addIfMissing(store, res, Namespaces.SCHEMA + "email", profile.email());
      addIfMissing(store, res, Namespaces.SCHEMA + "description", profile.bio());
      addIfMissingUriLiteral(store, res, Namespaces.SCHEMA + "image", profile.avatarUrl());
      stampNow(store, res);
    });
  }

  private static String githubUrl(String login) {
    return "https://github.com/" + login;
  }

  public String importProject(GitHubClient.RepoInfo repo, List<String> languages, String lastCommitDate) {
    try {
      Model rmlOutput = executeRml("github-repo.json", MAPPING_PROJECT, repo);
      Resource subject = findUriSubjectOfType(rmlOutput, Namespaces.CC + "Project");

      rdfRepository.write(store -> {
        upsertModel(store, rmlOutput);
        if (subject != null) enrichProject(store, subject.getURI(), repo, languages, lastCommitDate);
      });
      return subject != null ? subject.getURI() : null;
    }
    catch (Exception e) {
      log.error("RML project import failed for {}: {}", repo.name(), e.getMessage(), e);
      return null;
    }
  }

  private void enrichProject(Model store, String projectUri, GitHubClient.RepoInfo repo,
                             List<String> languages, String lastCommitDate) {
    Resource project = store.createResource(projectUri);
    setLiterals(store, project, Namespaces.SCHEMA + "keywords", repo.topics());

    setDate(store, project, Namespaces.SCHEMA + "dateModified",
            lastCommitDate != null ? lastCommitDate : repo.pushedAt());
    setBoolean(store, project, Namespaces.CC + "isArchived", repo.archived());
    setLiterals(store, project, Namespaces.DOAP + "programming-language", languages);
    stampNow(store, project);
  }

  public void createContribution(ContributionData data) {
    try {
      Model rmlOutput = executeRml("github-contribution.json", MAPPING_CONTRIBUTION, data);
      Resource subject = findUriSubjectOfType(rmlOutput, Namespaces.CC + "ProjectContribution");
      if (subject != null) {
        setDate(rmlOutput, subject, Namespaces.SCHEMA + "startDate", data.startDate());
        setDate(rmlOutput, subject, Namespaces.CC + "lastCommitDate", data.lastCommitDate());
        setInt(rmlOutput, subject, Namespaces.CC + "linesAdded", data.linesAdded());
        setInt(rmlOutput, subject, Namespaces.CC + "linesDeleted", data.linesDeleted());
      }
      rdfRepository.write(store -> store.add(rmlOutput));
    }
    catch (Exception e) {
      log.error("RML contribution create failed for {}: {}", data, e.getMessage(), e);
    }
  }

  public void updateContributionStats(String contributionUri, int commits, int reviews, int mergedPrs,
                                      String startDate, String lastCommitDate, int linesAdded, int linesDeleted) {
    rdfRepository.write(store -> {
      Resource subject = store.createResource(contributionUri);
      setInt(store, subject, Namespaces.CC + "commitCount", commits);
      setInt(store, subject, Namespaces.CC + "prReviewCount", reviews);
      setInt(store, subject, Namespaces.CC + "mergedPrCount", mergedPrs);
      setInt(store, subject, Namespaces.CC + "linesAdded", linesAdded);
      setInt(store, subject, Namespaces.CC + "linesDeleted", linesDeleted);
      setDate(store, subject, Namespaces.CC + "lastCommitDate", lastCommitDate);

      if (startDate != null && !store.contains(subject, store.createProperty(Namespaces.SCHEMA + "startDate"), (RDFNode) null)) {
        store.add(subject, store.createProperty(Namespaces.SCHEMA + "startDate"),
                  store.createTypedLiteral(startDate, XSDDatatype.XSDdate));
      }
      stampNow(store, subject);
    });
  }

  public Map<String, String> buildPrefixMap() {
    Map<String, String> map = new HashMap<>();
    for (Map<String, Object> org : readOrganizations()) {
      String id = (String) org.get("id");
      Object prefixes = org.get("githubPrefixes");
      if (id == null || !(prefixes instanceof List<?> list)) continue;
      for (Object prefix : list) {
        map.put(prefix.toString(), Namespaces.DATA + "Organization/" + id);
      }
    }
    return map;
  }

  public List<String> getInternalRepos() {
    Object repos = loadSeedConfig().get("internalRepos");
    if (repos instanceof List<?> list) return list.stream().map(Object::toString).toList();
    return List.of();
  }

  public void seedOrganizations() {
    try {
      List<Map<String, Object>> orgs = readOrganizations();
      if (orgs.isEmpty()) throw new IllegalStateException("No organizations found in seed file");
      enrichFromWikidata(orgs);
      Model rmlOutput = executeRml("known-organizations.json", MAPPING_SEED_ORG, orgs);
      rdfRepository.write(store -> upsertModel(store, rmlOutput));
      rdfRepository.write(store -> seedOffices(store, orgs));
      log.info("Seeded {} organisations", orgs.size());
    }
    catch (Exception e) {
      log.error("Organisation seed failed: {}", e.getMessage(), e);
    }
  }

  private void enrichFromWikidata(List<Map<String, Object>> orgs) {
    for (Map<String, Object> org : orgs) {
      Object sameAs = org.get("sameAs");
      if (sameAs == null || sameAs.toString().isBlank()) continue;
      Map<String, Object> wikidataFields = wikidata.fetch(sameAs.toString());
      wikidataFields.forEach(org::putIfAbsent);
    }
  }

  private void seedOffices(Model store, List<Map<String, Object>> orgs) {
    for (Map<String, Object> org : orgs) {
      String id = (String) org.get("id");
      if (id == null || !(org.get("offices") instanceof List<?> offices)) continue;
      Resource orgRes = store.createResource(Namespaces.DATA + "Organization/" + id);
      for (Object entry : offices) {
        if (!(entry instanceof Map<?, ?> office)) continue;
        Offices.set(store, orgRes,
          str(office.get("city")), str(office.get("country")),
          str(office.get("street")), str(office.get("postCode")));
      }
    }
  }

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> readOrganizations() {
    Object orgs = loadSeedConfig().get("organizations");
    return orgs instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> loadSeedConfig() {
    try (InputStream is = getClass().getResourceAsStream(SEED_CONFIG)) {
      if (is == null) return Map.of();
      return objectMapper.readValue(is, Map.class);
    }
    catch (Exception e) {
      log.warn("Could not load seed config: {}", e.getMessage());
      return Map.of();
    }
  }

  public Set<String> getIgnoredLogins() {
    return loadPersonOverrides().ignoredLogins().stream()
                                .map(String::toLowerCase)
                                .collect(Collectors.toSet());
  }

  public Map<String, String> buildGithubLoginToSlackId() {
    return loadPersonOverrides().identityLinks().stream()
                                .collect(Collectors.toMap(
                                  link -> link.githubLogin().toLowerCase(),
                                  link -> link.slackId(),
                                  (existing, replacement) -> replacement));
  }

  private PersonOverrides loadPersonOverrides() {
    try (InputStream is = getClass().getResourceAsStream(PERSON_OVERRIDES)) {
      if (is == null) return PersonOverrides.empty();
      return objectMapper.readValue(is, PersonOverrides.class);
    }
    catch (Exception e) {
      log.warn("Could not load {}: {}", PERSON_OVERRIDES, e.getMessage());
      return PersonOverrides.empty();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PersonOverrides(List<String> ignoredLogins, List<IdentityLink> identityLinks) {
    private PersonOverrides {
      ignoredLogins = ignoredLogins == null ? List.of() : ignoredLogins;
      identityLinks = identityLinks == null ? List.of() : identityLinks;
    }

    private static PersonOverrides empty() {
      return new PersonOverrides(List.of(), List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityLink(String githubLogin, String slackId, String person) {}
  }

  private Model executeRml(String jsonFilename, String mappingResource, Object data) throws Exception {
    Path tempDir = Files.createTempDirectory("rml-github-");
    try {
      Files.writeString(tempDir.resolve(jsonFilename), objectMapper.writeValueAsString(data));
      Path mappingFile = copyMappingToTemp(mappingResource, tempDir);

      QuadStore rmlStore = QuadStoreFactory.read(mappingFile.toFile());
      RDF4JStore outputStore = new RDF4JStore();
      new Executor(rmlStore, new RecordsFactory(tempDir.toString()), outputStore, null, null).execute(null);

      return toJenaModel(outputStore);
    }
    finally {
      deleteTempDir(tempDir);
    }
  }

  private Path copyMappingToTemp(String mappingResource, Path tempDir) throws IOException {
    Path mappingFile = tempDir.resolve("mapping.ttl");
    try (InputStream ms = getClass().getResourceAsStream(mappingResource)) {
      if (ms == null) throw new IllegalStateException("RML mapping not found on classpath: " + mappingResource);
      Files.copy(ms, mappingFile);
    }
    return mappingFile;
  }

  private static Model toJenaModel(RDF4JStore outputStore) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    outputStore.write(out, "ntriples");
    Model model = ModelFactory.createDefaultModel();
    RDFDataMgr.read(model, new ByteArrayInputStream(out.toByteArray()), Lang.NTRIPLES);
    return model;
  }

  private static void deleteTempDir(Path tempDir) {
    if (tempDir == null) return;
    try (Stream<Path> files = Files.walk(tempDir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
      });
    }
    catch (IOException ignored) {}
  }

  private static Resource findUriSubjectOfType(Model model, String classUri) {
    return model.listSubjectsWithProperty(RDF.type, model.createResource(classUri))
                .filterKeep(Resource::isURIResource)
                .nextOptional()
                .orElse(null);
  }

  private static void upsertModel(Model store, Model incoming) {
    incoming.listStatements().forEachRemaining(stmt -> {
      if (!RDF.type.equals(stmt.getPredicate())) {
        store.removeAll(stmt.getSubject(), stmt.getPredicate(), null);
      }
      store.add(stmt);
    });
  }
}
