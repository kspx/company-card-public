package zone.cogni.companycard.service;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.repository.RdfRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import zone.cogni.companycard.service.SlackClient.SlackTeamInfo;
import zone.cogni.companycard.service.SlackClient.SlackUser;

@Service
public class SlackSyncService {
  private static final Logger log = LoggerFactory.getLogger(SlackSyncService.class);
  private static final JaroWinklerSimilarity SIMILARITY = new JaroWinklerSimilarity();
  private static final double FUZZY_THRESHOLD = 0.85;

  private static final Property CC_PERSON          = prop(Namespaces.CC, "Person");
  private static final Property CC_ORGANIZATION    = prop(Namespaces.CC, "Organization");
  private static final Property CC_EMPLOYMENT      = prop(Namespaces.CC, "Employment");
  private static final Property CC_EMPLOYMENT_TYPE = prop(Namespaces.CC, "employmentType");
  private static final Property CC_EMPLOYMENT_OF   = prop(Namespaces.CC, "employmentOf");
  private static final Property CC_EMPLOYMENT_WITH = prop(Namespaces.CC, "employmentWith");
  private static final Property SCHEMA_GIVEN_NAME  = prop(Namespaces.SCHEMA, "givenName");
  private static final Property SCHEMA_FAMILY_NAME = prop(Namespaces.SCHEMA, "familyName");
  private static final Property SCHEMA_EMAIL       = prop(Namespaces.SCHEMA, "email");
  private static final Property SCHEMA_TELEPHONE   = prop(Namespaces.SCHEMA, "telephone");
  private static final Property SCHEMA_IMAGE       = prop(Namespaces.SCHEMA, "image");
  private static final Property SCHEMA_URL         = prop(Namespaces.SCHEMA, "url");
  private static final Property SCHEMA_START_DATE  = prop(Namespaces.SCHEMA, "startDate");
  private static final Property ROV_LEGAL_NAME     = prop(Namespaces.ROV, "legalName");
  private static final Property DC_SOURCE          = prop(Namespaces.DCT, "source");
  private static final Property DC_MODIFIED        = prop(Namespaces.DCT, "modified");
  private static final String   CCV_FULL_TIME      = Namespaces.CCV + "FULL_TIME";

  @Value("${app.slack.organizationUri:}")
  private String organizationUri;

  private final SlackClient slackClient;
  private final RdfRepository rdfRepository;
  private final UriMinter uriMinter;

  public SlackSyncService(SlackClient slackClient, RdfRepository rdfRepository, UriMinter uriMinter) {
    this.slackClient = slackClient;
    this.rdfRepository = rdfRepository;
    this.uriMinter = uriMinter;
  }

  public void syncNow() {
    log.info("=== Slack sync started ===");
    long start = System.currentTimeMillis();
    try {
      String orgUri = syncOrganization();
      syncPersons(orgUri);
    }
    catch (Exception e) {
      log.error("Slack sync failed: {}", e.getMessage(), e);
    }
    log.info("=== Slack sync done in {}ms ===", System.currentTimeMillis() - start);
  }

  private String syncOrganization() {
    try {
      SlackTeamInfo info = slackClient.getWorkspaceInfo();
      String uri = StringUtils.hasText(organizationUri)
        ? organizationUri
        : Namespaces.DATA + "Organization/" + normalize(info.name());
      rdfRepository.write(model -> {
        Resource org = model.createResource(uri);
        model.add(org, RDF.type,  CC_ORGANIZATION.asResource());
        model.add(org, DC_SOURCE, model.createResource(Namespaces.CCV_SLACK));
        addIfMissing(model, org, ROV_LEGAL_NAME, info.name());
        addUriIfMissing(model, org, SCHEMA_URL,   info.publicUrl());
        addUriIfMissing(model, org, SCHEMA_IMAGE, info.iconUrl());
        stampNow(model, org);
      });
      log.info("  Organization: {}", uri);
      return uri;
    }
    catch (IllegalStateException e) {
      if (isMissingScope(e)) {
        log.warn("  Skipping workspace info — add 'team:read' scope to import organization");
        return StringUtils.hasText(organizationUri) ? organizationUri : null;
      }
      throw e;
    }
  }

  private void syncPersons(String orgUri) {
    PersonIndex index = buildPersonIndex();
    Map<String, String> joinDates = fetchGeneralChannelJoinDates();

    List<SlackUser> users = slackClient.getWorkspaceUsers();
    log.info("  {} Slack users, {} existing persons indexed", users.size(), index.size());

    int matched = 0, created = 0;
    for (SlackUser user : users) {
      String uri = index.resolve(user);
      if (uri == null) { uri = createStub(user); created++; log.info("  [stub] {}", user.realName()); }
      else             { matched++;               log.info("  [match] {}", user.realName()); }
      try { enrichPerson(uri, user, orgUri, joinDates); }
      catch (Exception e) { log.warn("  Failed to enrich person {}: {}", uri, e.getMessage()); }
    }
    log.info("  Persons: {} matched, {} new stubs", matched, created);
  }

  private Map<String, String> fetchGeneralChannelJoinDates() {
    try { return slackClient.getChannelJoinDates("general"); }
    catch (IllegalStateException e) {
      if (isMissingScope(e)) log.warn("  Skipping #general join dates — add 'channels:read' + 'channels:history' scopes");
      else log.warn("  Could not fetch #general join dates: {}", e.getMessage());
      return Map.of();
    }
  }

  private PersonIndex buildPersonIndex() {
    PersonIndex index = new PersonIndex();
    rdfRepository.read(model -> {
      model.listSubjectsWithProperty(RDF.type, CC_PERSON.asResource()).forEach(person -> {
        AdmsIdentifiers.notation(person, Namespaces.CCV_SLACK).ifPresent(id -> index.bySlackId.put(id, person.getURI()));
        str(person, SCHEMA_EMAIL)  .ifPresent(em -> index.byEmail  .put(em.toLowerCase(), person.getURI()));
        str(person, SCHEMA_GIVEN_NAME).flatMap(gn ->
          str(person, SCHEMA_FAMILY_NAME).map(fn -> normalize(gn + " " + fn)))
          .ifPresent(name -> index.byName.put(name, person.getURI()));
        AdmsIdentifiers.notation(person, Namespaces.CCV_GITHUB).ifPresent(gh -> {
          index.byHandle.put(gh.toLowerCase(), person.getURI());
          index.byHandle.put(normalize(gh), person.getURI());
        });
      });
      return null;
    });
    return index;
  }

  private static final class PersonIndex {
    final Map<String, String> bySlackId = new HashMap<>();
    final Map<String, String> byEmail   = new HashMap<>();
    final Map<String, String> byName    = new HashMap<>();
    final Map<String, String> byHandle  = new HashMap<>();

    int size() { return bySlackId.size() + byEmail.size(); }

    String resolve(SlackUser user) {
      String normalizedName   = normalize(nameFor(user));
      String normalizedHandle = normalize(user.handle());
      return Stream.of(
          bySlackId.get(user.id()),
          user.email()  != null ? byEmail.get(user.email().toLowerCase())   : null,
          byName.get(normalizedName),
          user.handle() != null ? byHandle.get(user.handle().toLowerCase()) : null,
          byHandle.get(normalizedHandle),
          fuzzyMatch(byHandle, normalizedName, normalizedHandle)
        ).filter(Objects::nonNull).findFirst().orElse(null);
    }
  }

  private static String fuzzyMatch(Map<String, String> byHandle, String name, String handle) {
    for (String probe : List.of(name, handle)) {
      if (probe.length() < 5) continue;
      for (Map.Entry<String, String> e : byHandle.entrySet()) {
        if (e.getKey().length() >= 5 && SIMILARITY.apply(probe, e.getKey()) >= FUZZY_THRESHOLD) return e.getValue();
      }
    }
    return null;
  }

  private String createStub(SlackUser user) {
    String base = uriMinter.mint("Person",
      Arrays.asList(firstNameOf(user), lastNameOf(user)),
      Arrays.asList(user.handle(), user.id()));
    String[] uri = {base};
    rdfRepository.write(model -> {
      uri[0] = uriMinter.ensureUnique(base, model);
      Resource res = model.createResource(uri[0]);
      model.add(res, RDF.type, CC_PERSON.asResource());
      AdmsIdentifiers.set(model, res, Namespaces.CCV_SLACK, user.id());
      addIfMissing(model, res, SCHEMA_GIVEN_NAME,  firstNameOf(user));
      addIfMissing(model, res, SCHEMA_FAMILY_NAME, lastNameOf(user));
      addIfMissing(model, res, SCHEMA_EMAIL,       user.email());
      addIfMissing(model, res, SCHEMA_TELEPHONE,   user.phone());
      addUriIfMissing(model, res, SCHEMA_IMAGE,    user.imageUrl());
    });
    return uri[0];
  }

  private void enrichPerson(String personUri, SlackUser user, String orgUri, Map<String, String> joinDates) {
    rdfRepository.write(model -> {
      Resource res = model.createResource(personUri);
      AdmsIdentifiers.set(model, res, Namespaces.CCV_SLACK, user.id());

      addIfMissing   (model, res, SCHEMA_GIVEN_NAME,  firstNameOf(user));
      addIfMissing   (model, res, SCHEMA_FAMILY_NAME, lastNameOf(user));
      addIfMissing   (model, res, SCHEMA_EMAIL,       user.email());
      addIfMissing   (model, res, SCHEMA_TELEPHONE,   user.phone());
      addUriIfMissing(model, res, SCHEMA_IMAGE,       user.imageUrl());
      if (orgUri != null) ensureEmployment(model, res, personUri, orgUri, user, joinDates);
      stampNow(model, res);
    });
  }

  private void ensureEmployment(Model model, Resource person, String personUri, String orgUri,
                                SlackUser user, Map<String, String> joinDates) {
    Resource org = model.createResource(orgUri);

    List<Resource> existing = model.listSubjectsWithProperty(CC_EMPLOYMENT_OF, person)
      .filterKeep(e -> model.contains(e, CC_EMPLOYMENT_WITH, org))
      .toList();

    Resource employment = existing.isEmpty()
      ? createEmployment(model, person, org, personUri, orgUri)
      : existing.get(0);

    if (!model.contains(employment, SCHEMA_START_DATE)) {
      String date = joinDates.getOrDefault(user.id(), slackAccountDate(user));
      if (date != null) {
        model.add(employment, SCHEMA_START_DATE, model.createTypedLiteral(date, XSDDatatype.XSDdate));
      }
    }
  }

  private Resource createEmployment(Model model, Resource person, Resource org,
                                    String personUri, String orgUri) {
    Resource emp = model.createResource(
      uriMinter.mint("Employment", Arrays.asList(UriUtils.extractLocalName(personUri), UriUtils.extractLocalName(orgUri)), List.of()));
    model.add(emp, RDF.type,           CC_EMPLOYMENT.asResource());
    model.add(emp, CC_EMPLOYMENT_OF,   person);
    model.add(emp, CC_EMPLOYMENT_WITH, org);
    model.add(emp, CC_EMPLOYMENT_TYPE, model.createResource(CCV_FULL_TIME));
    log.info("  Created Employment for {}", personUri);
    return emp;
  }

  private static String firstNameOf(SlackUser u) {
    if (u.firstName() != null) return u.firstName();
    return u.realName() == null ? null : u.realName().trim().split(" ", 2)[0];
  }

  private static String lastNameOf(SlackUser u) {
    if (u.lastName() != null) return u.lastName();
    if (u.realName() == null) return null;
    String trimmed = u.realName().trim();
    int space = trimmed.indexOf(' ');
    return space < 0 ? null : trimmed.substring(space + 1);
  }

  private static String nameFor(SlackUser u) {
    if (u.firstName() != null && u.lastName() != null) return u.firstName() + " " + u.lastName();
    return u.realName() != null ? u.realName() : "";
  }

  private static String slackAccountDate(SlackUser u) {
    if (u.created() <= 0) return null;
    return Instant.ofEpochSecond(u.created()).atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private static Property prop(String ns, String localName) {
    return ResourceFactory.createProperty(ns + localName);
  }

  private static Optional<String> str(Resource r, Property p) {
    Statement s = r.getProperty(p);
    return Optional.ofNullable(s == null ? null : s.getString());
  }

  private static String normalize(String s) {
    return s == null ? "" : s.toLowerCase().replaceAll("[^a-z]", "");
  }

  private static boolean isMissingScope(Exception e) {
    return e.getMessage() != null && e.getMessage().contains("missing_scope");
  }

  private static void addIfMissing(Model m, Resource r, Property p, String value) {
    if (StringUtils.hasText(value) && !m.contains(r, p)) m.add(r, p, value);
  }

  private static void addUriIfMissing(Model m, Resource r, Property p, String value) {
    if (StringUtils.hasText(value) && !m.contains(r, p)) {
      m.add(r, p, m.createTypedLiteral(value, XSDDatatype.XSDanyURI));
    }
  }

  private static void stampNow(Model m, Resource r) {
    m.removeAll(r, DC_MODIFIED, null);
    m.add(r, DC_MODIFIED, m.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime));
  }
}
