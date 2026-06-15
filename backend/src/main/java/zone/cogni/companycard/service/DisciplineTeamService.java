package zone.cogni.companycard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.DisciplineTeamConfig;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class DisciplineTeamService {
  private static final Logger log = LoggerFactory.getLogger(DisciplineTeamService.class);
  private static final String CONFIG = "/seed/discipline-teams.json";
  private static final List<String> SQUAD_DISCIPLINES = List.of("backend", "frontend");
  private static final String DESIGN_TEAM_ID = "design";
  private static final int STALE_YEARS = 2;

  private final RdfRepository repository;
  private final ObjectMapper objectMapper;

  public DisciplineTeamService(RdfRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public int seedDisciplineTeams() {
    return materialize(orgTeamSpecs(loadConfig(), requireOwnOrg()), List.of());
  }

  public Map<String, Object> applyClassification() {
    DisciplineTeamConfig config = loadConfig();
    GraphFacts facts = readFacts();

    List<TeamSpec> teams = new ArrayList<>(orgTeamSpecs(config, facts.ownOrg()));
    List<MembershipSpec> memberships = new ArrayList<>(orgMemberships(config, facts));
    addSquads(config, facts, teams, memberships);
    addDesignSquads(config, facts, teams, memberships);

    Result result = write(teams, memberships, facts.existingMemberships());
    log.info("Discipline teams applied: {} teams, {} memberships", result.teams(), result.memberships());
    return Map.of("teamsCreated", result.teams(), "membershipsCreated", result.memberships());
  }

  private List<TeamSpec> orgTeamSpecs(DisciplineTeamConfig config, String ownOrg) {
    return config.teams().stream()
      .map(t -> new TeamSpec(teamUri(t.id()), t.name(), t.teamType(), ownOrg, List.of(), Namespaces.CCV_MANUAL))
      .toList();
  }

  private List<MembershipSpec> orgMemberships(DisciplineTeamConfig config, GraphFacts facts) {
    List<MembershipSpec> out = new ArrayList<>();
    config.disciplines().forEach(m -> {
      String p = facts.resolve(m.person(), m.personUri());
      out.add(new MembershipSpec(p, teamUri(m.discipline()), "MEMBERSHIP_MEMBER", facts.startFor(p), null));
    });
    config.managers().forEach(m -> {
      String p = facts.resolve(m.person(), m.personUri());
      out.add(new MembershipSpec(p, teamUri(config.managementTeamId()), "MEMBERSHIP_MANAGER", facts.startFor(p), null));
    });
    config.designers().forEach(d -> {
      String p = facts.resolve(d.person(), d.personUri());
      out.add(new MembershipSpec(p, teamUri(DESIGN_TEAM_ID), "MEMBERSHIP_MEMBER", facts.startFor(p), null));
    });
    return out;
  }

  private void addSquads(DisciplineTeamConfig config, GraphFacts facts,
                         List<TeamSpec> teams, List<MembershipSpec> memberships) {
    Map<String, String> disciplineByPerson = new HashMap<>();
    config.disciplines().forEach(m -> disciplineByPerson.put(facts.resolve(m.person(), m.personUri()), m.discipline()));

    facts.projectsByClient().forEach((client, projects) -> {
      String clientLocal = UriUtils.extractLocalName(client);
      Set<String> members = new HashSet<>();
      projects.forEach(p -> members.addAll(facts.contributorsOf(p)));
      for (String discipline : SQUAD_DISCIPLINES) {
        List<String> squad = members.stream().filter(p -> discipline.equals(disciplineByPerson.get(p))).toList();
        if (squad.isEmpty()) continue;
        String squadUri = teamUri(clientLocal + "-" + discipline);
        teams.add(new TeamSpec(squadUri, clientLocal + " " + capitalize(discipline),
          "TEAM_DEVELOPMENT", facts.ownOrg(), List.copyOf(projects), Namespaces.CCV_DERIVED));
        squad.forEach(person -> memberships.add(new MembershipSpec(person, squadUri, "MEMBERSHIP_MEMBER",
          facts.startForClient(person, projects), facts.endForClient(person, projects))));
      }
    });
  }

  private void addDesignSquads(DisciplineTeamConfig config, GraphFacts facts,
                               List<TeamSpec> teams, List<MembershipSpec> memberships) {
    Map<String, String> clientByLocal = new HashMap<>();
    facts.projectsByClient().keySet().forEach(client -> clientByLocal.put(UriUtils.extractLocalName(client), client));

    config.designers().forEach(designer -> designer.clients().forEach(clientLocal -> {
      String client = clientByLocal.get(clientLocal);
      if (client == null) {
        log.warn("Design assignment for {} skipped — no client org '{}' with projects in the graph", designer.name(), clientLocal);
        return;
      }
      Set<String> projects = facts.projectsByClient().get(client);
      String squadUri = teamUri(clientLocal + "-" + DESIGN_TEAM_ID);
      teams.add(new TeamSpec(squadUri, clientLocal + " " + capitalize(DESIGN_TEAM_ID),
        "TEAM_DESIGN", facts.ownOrg(), List.copyOf(projects), Namespaces.CCV_DERIVED));
      String p = facts.resolve(designer.person(), designer.personUri());
      memberships.add(new MembershipSpec(p, squadUri, "MEMBERSHIP_MEMBER",
        facts.startForClient(p, projects), facts.endForClient(p, projects)));
    }));
  }

  private Result write(List<TeamSpec> teams, List<MembershipSpec> memberships, Set<String> existingMemberships) {
    int[] teamCount = {0};
    int[] membershipCount = {0};
    Set<String> seen = new HashSet<>(existingMemberships);
    repository.write(model -> {
      teams.forEach(t -> {if (writeTeam(model, t)) teamCount[0]++;});
      memberships.forEach(m -> {if (writeMembership(model, m, seen)) membershipCount[0]++;});
    });
    return new Result(teamCount[0], membershipCount[0]);
  }

  private boolean writeTeam(Model model, TeamSpec spec) {
    Resource team = model.createResource(spec.uri());
    if (model.contains(team, RDF.type, (RDFNode) null)) return false;
    team.addProperty(RDF.type, model.createResource(Namespaces.CC + "Team"));
    text(team, Namespaces.CC + "teamName", spec.name());
    link(team, Namespaces.CC + "teamType", Namespaces.CCV + spec.teamType());
    link(team, Namespaces.CC + "teamOf", spec.orgUri());
    spec.projectUris().forEach(p -> link(team, Namespaces.CC + "developsProject", p));
    link(team, Namespaces.DCT + "source", spec.sourceUri());
    return true;
  }

  private boolean writeMembership(Model model, MembershipSpec spec, Set<String> seen) {
    if (!seen.add(spec.personUri() + "\t" + spec.teamUri())) return false;
    Resource node = model.createResource(
      Namespaces.DATA + "TeamMembership/" + UriUtils.extractLocalName(spec.personUri()) + "-" + UriUtils.extractLocalName(spec.teamUri()));
    if (model.contains(node, RDF.type, (RDFNode) null)) return false;
    node.addProperty(RDF.type, model.createResource(Namespaces.CC + "TeamMembership"));
    link(node, Namespaces.CC + "membershipOf", spec.personUri());
    link(node, Namespaces.CC + "membershipIn", spec.teamUri());
    link(node, Namespaces.CC + "membershipRole", Namespaces.CCV + spec.role());
    if (spec.startDate() != null) date(node, Namespaces.SCHEMA + "startDate", spec.startDate().substring(0, 10));
    if (spec.endDate() != null) date(node, Namespaces.SCHEMA + "endDate", spec.endDate().substring(0, 10));
    link(node, Namespaces.DCT + "source", Namespaces.CCV_DERIVED);
    return true;
  }

  private GraphFacts readFacts() {
    String ownOrg = requireOwnOrg();

    Map<String, Set<String>> contributors = new HashMap<>();
    query(SparqlQueries.findProjectContributors(),
      row -> pair(SparqlExecutor.getUri(row, "project"), SparqlExecutor.getUri(row, "person")))
      .forEach(p -> {if (p != null) contributors.computeIfAbsent(p.getKey(), k -> new HashSet<>()).add(p.getValue());});

    Map<String, Set<String>> projectsByClient = new HashMap<>();
    query(SparqlQueries.findProjectClients(),
      row -> pair(SparqlExecutor.getUri(row, "client"), SparqlExecutor.getUri(row, "project")))
      .forEach(p -> {if (p != null) projectsByClient.computeIfAbsent(p.getKey(), k -> new HashSet<>()).add(p.getValue());});

    Map<String, String> personStart = new HashMap<>();
    query(SparqlQueries.findEarliestContributionDatePerPerson(),
      row -> pair(SparqlExecutor.getUri(row, "person"), SparqlExecutor.getString(row, "startDate")))
      .forEach(p -> {if (p != null) personStart.put(p.getKey(), p.getValue());});

    Map<String, String> pairStart = new HashMap<>();
    query(SparqlQueries.findEarliestContributionDatePerPersonProject(), row -> {
      String person = SparqlExecutor.getUri(row, "person");
      String project = SparqlExecutor.getUri(row, "project");
      String date = SparqlExecutor.getString(row, "startDate");
      return (person != null && project != null && date != null) ? Map.entry(person + "\t" + project, date) : null;
    }).forEach(p -> {if (p != null) pairStart.put(p.getKey(), p.getValue());});

    Map<String, String> pairEnd = new HashMap<>();
    query(SparqlQueries.findLatestContributionDatePerPersonProject(), row -> {
      String person = SparqlExecutor.getUri(row, "person");
      String project = SparqlExecutor.getUri(row, "project");
      String date = SparqlExecutor.getString(row, "endDate");
      return (person != null && project != null && date != null) ? Map.entry(person + "\t" + project, date) : null;
    }).forEach(p -> {if (p != null) pairEnd.put(p.getKey(), p.getValue());});

    Set<String> existing = new HashSet<>();
    query(SparqlQueries.findAllTeamMembershipPairs(),
      row -> pair(SparqlExecutor.getUri(row, "person"), SparqlExecutor.getUri(row, "team")))
      .forEach(p -> {if (p != null) existing.add(p.getKey() + "\t" + p.getValue());});

    Map<String, String> personBySlackId = new HashMap<>();
    query(SparqlQueries.findAllPersonsWithSlackId(),
      row -> pair(SparqlExecutor.getString(row, "slackId"), SparqlExecutor.getUri(row, "person")))
      .forEach(p -> {if (p != null) personBySlackId.put(p.getKey(), p.getValue());});

    String staleCutoff = LocalDate.now().minusYears(STALE_YEARS).toString();
    return new GraphFacts(ownOrg, contributors, projectsByClient, personStart, pairStart, pairEnd, staleCutoff, existing, personBySlackId);
  }

  private String requireOwnOrg() {
    return query(SparqlQueries.findOwnOrganization(), row -> SparqlExecutor.getUri(row, "org")).stream()
      .filter(java.util.Objects::nonNull).findFirst()
      .orElseThrow(() -> new IllegalStateException("No own organisation in the graph — run the Slack sync first"));
  }

  private <T> List<T> query(String sparql, Function<QuerySolution, T> mapper) {
    return repository.read(model -> SparqlExecutor.select(model, sparql, mapper));
  }

  private DisciplineTeamConfig loadConfig() {
    try (InputStream is = getClass().getResourceAsStream(CONFIG)) {
      if (is == null) throw new IllegalStateException("Missing classpath resource " + CONFIG);
      return objectMapper.readValue(is, DisciplineTeamConfig.class);
    }
    catch (Exception e) {
      throw new IllegalStateException("Could not load " + CONFIG + ": " + e.getMessage(), e);
    }
  }

  private int materialize(List<TeamSpec> teams, List<MembershipSpec> memberships) {
    return write(teams, memberships, Set.of()).teams();
  }

  private static String teamUri(String id) {
    return Namespaces.DATA + "Team/" + id;
  }

  private static String capitalize(String value) {
    return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static Map.Entry<String, String> pair(String key, String value) {
    return (key != null && value != null) ? Map.entry(key, value) : null;
  }

  private static void link(Resource subject, String property, String objectUri) {
    subject.addProperty(subject.getModel().createProperty(property), subject.getModel().createResource(objectUri));
  }

  private static void text(Resource subject, String property, String value) {
    subject.addProperty(subject.getModel().createProperty(property), subject.getModel().createTypedLiteral(value, XSDDatatype.XSDstring));
  }

  private static void date(Resource subject, String property, String value) {
    subject.addProperty(subject.getModel().createProperty(property), subject.getModel().createTypedLiteral(value, XSDDatatype.XSDdate));
  }

  private record TeamSpec(String uri, String name, String teamType, String orgUri, List<String> projectUris, String sourceUri) {}

  private record MembershipSpec(String personUri, String teamUri, String role, String startDate, String endDate) {}

  private record Result(int teams, int memberships) {}

  private record GraphFacts(String ownOrg, Map<String, Set<String>> contributorsByProject,
                            Map<String, Set<String>> projectsByClient,
                            Map<String, String> personStart, Map<String, String> pairStart,
                            Map<String, String> pairEnd, String staleCutoff,
                            Set<String> existingMemberships, Map<String, String> personBySlackId) {
    Set<String> contributorsOf(String projectUri) {
      return contributorsByProject.getOrDefault(projectUri, Set.of());
    }

    String resolve(String slackId, String fallbackUri) {
      String uri = slackId == null ? null : personBySlackId.get(slackId);
      return uri != null ? uri : fallbackUri;
    }

    String startFor(String personUri) {
      return personStart.get(personUri);
    }

    String startForClient(String personUri, Set<String> projectUris) {
      return projectUris.stream()
        .map(project -> pairStart.get(personUri + "\t" + project))
        .filter(java.util.Objects::nonNull)
        .min(String::compareTo)
        .orElse(null);
    }

    String endForClient(String personUri, Set<String> projectUris) {
      String lastActivity = projectUris.stream()
        .map(project -> pairEnd.get(personUri + "\t" + project))
        .filter(java.util.Objects::nonNull)
        .max(String::compareTo)
        .orElse(null);
      return (lastActivity != null && lastActivity.substring(0, 10).compareTo(staleCutoff) < 0) ? lastActivity : null;
    }
  }
}
