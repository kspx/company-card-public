package zone.cogni.companycard.service;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import zone.cogni.companycard.service.GitHubClient.SearchResult;
import zone.cogni.companycard.service.GitHubClient.UserProfile;

import static zone.cogni.companycard.repository.ModelWrite.setDate;
import static zone.cogni.companycard.repository.ModelWrite.setResource;

@Service
public class GitHubSyncService {
  private static final Logger log = LoggerFactory.getLogger(GitHubSyncService.class);
  private static final String GITHUB_BASE = "https://github.com/";

  private static final long SEARCH_API_DELAY_MS = 2100;

  private final GitHubClient gitHubClient;
  private final GitHubRmlService gitHubRmlService;
  private final RdfRepository rdfRepository;
  private final SyncCoordinator syncCoordinator;

  @Value("${app.github.sync.enabled:true}")
  private boolean syncEnabled;
  @Value("${app.github.org:cognizone}")
  private String org;

  private Map<String, String> cachedPrefixMap;
  private List<String> cachedInternalRepos;

  private volatile Set<String> lastSkippedRepos = Set.of();

  public GitHubSyncService(GitHubClient gitHubClient, GitHubRmlService gitHubRmlService,
                           RdfRepository rdfRepository, SyncCoordinator syncCoordinator) {
    this.gitHubClient = gitHubClient;
    this.gitHubRmlService = gitHubRmlService;
    this.rdfRepository = rdfRepository;
    this.syncCoordinator = syncCoordinator;
  }

  private record RepoStats(String repo,
                           Map<String, Integer> commits,
                           Map<String, String> firstDates,
                           Map<String, String> lastDates,
                           Map<String, Integer> linesAdded,
                           Map<String, Integer> linesDeleted) {
    static RepoStats empty(String repo) {
      return new RepoStats(repo, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
  }

  private record Activity(int commits, int reviews, int mergedPrs,
                          String firstCommitDate, String lastCommitDate, String fallbackDate,
                          int linesAdded, int linesDeleted) {}

  @Scheduled(cron = "${app.github.sync.cron:0 0 0 * * MON}")
  public void scheduledSync() {
    if (!syncEnabled) {
      log.info("GitHub sync is disabled — skipping scheduled run");
      return;
    }
    log.info("Starting scheduled GitHub sync...");
    if (!syncCoordinator.tryRunAsync("scheduled-github", this::syncNow)) {
      log.warn("Scheduled GitHub sync skipped — another sync is already running");
    }
  }

  public void syncNow() {
    log.info("=== GitHub sync started ===");
    long start = System.currentTimeMillis();
    importOrgMembers();
    importRepos();
    syncRepoStats();
    upsertClientEngagements();
    inferPersonClientLinks();
    updateEngagementLifecycle();
    updateProjectStatus();
    deriveAvailability();
    log.info("=== GitHub sync done in {}ms ===", System.currentTimeMillis() - start);
  }

  public void importOrgMembers() {
    log.info("Importing org members from GitHub...");
    try {
      List<String> logins = gitHubClient.getOrgMemberLogins();
      log.info("  {} members found — upserting all", logins.size());

      List<UserProfile> profiles = fetchProfilesInParallel(logins);
      Set<String> ignoredLogins = gitHubRmlService.getIgnoredLogins();
      Map<String, String> slackIdByLogin = gitHubRmlService.buildGithubLoginToSlackId();
      Map<String, String> reconcileIndex = buildPersonReconcileIndex();
      int created = 0, updated = 0, reconciled = 0, skipped = 0;
      for (UserProfile profile : profiles) {
        if (ignoredLogins.contains(profile.login().toLowerCase())) {
          log.info("  Skipped ignored login: {}", profile.login());
          skipped++;
          continue;
        }
        int n = created + updated + reconciled + 1;
        String byGithubLogin = findPersonByGithubLogin(profile.login());
        if (byGithubLogin != null) {
          gitHubRmlService.attachGithubIdentity(byGithubLogin, profile);
          log.info("  [{}/{}] Updated: {}", n, profiles.size(), byGithubLogin);
          updated++;
          continue;
        }
        String slackId = slackIdByLogin.get(profile.login().toLowerCase());
        String existingPerson = slackId != null ? findPersonBySlackId(slackId) : null;
        if (existingPerson == null) existingPerson = matchExistingPerson(profile, reconcileIndex);
        if (existingPerson != null) {
          gitHubRmlService.attachGithubIdentity(existingPerson, profile);
          log.info("  [{}/{}] Reconciled {} → {}", n, profiles.size(), profile.login(), existingPerson);
          reconciled++;
          continue;
        }
        if (gitHubRmlService.importPerson(profile) == null) continue;
        log.info("  [{}/{}] Created: {}", n, profiles.size(), profile.login());
        created++;
      }
      log.info("Member import complete: {} created, {} updated, {} reconciled, {} skipped",
        created, updated, reconciled, skipped);
    }
    catch (Exception e) {
      log.error("Failed to import org members: {}", e.getMessage(), e);
    }
  }

  private List<UserProfile> fetchProfilesInParallel(List<String> logins) {
    List<CompletableFuture<UserProfile>> futures = logins.stream()
                                                         .map(gitHubClient::getUserProfileAsync)
                                                         .toList();
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                     .join();
    return futures.stream()
                  .map(CompletableFuture::join)
                  .filter(Objects::nonNull)
                  .toList();
  }

  public void importRepos() {
    log.info("Importing org repos from GitHub...");
    try {
      List<GitHubClient.RepoInfo> repos = gitHubClient.getRepos();
      log.info("  {} repos found — upserting all", repos.size());

      int created = 0, updated = 0;
      for (GitHubClient.RepoInfo repo : repos) {
        boolean exists = findProjectByRepoUrl(GITHUB_BASE + org + "/" + repo.name()) != null;
        List<String> languages = fetchLanguagesSafe(repo.name());
        String lastCommitDate = gitHubClient.getLastCommitDate(repo.name());
        String projectUri = gitHubRmlService.importProject(repo, languages, lastCommitDate);
        if (projectUri == null) continue;

        linkToClient(projectUri, repo.name());
        if (exists) {
          log.info("  [{}/{}] Updated: {}", created + updated + 1, repos.size(), repo.name());
          updated++;
        }
        else {
          log.info("  [{}/{}] Created: {}", created + updated + 1, repos.size(), repo.name());
          created++;
        }
      }
      log.info("Repo import complete: {} created, {} updated", created, updated);
    }
    catch (Exception e) {
      log.error("Failed to import org repos: {}", e.getMessage(), e);
    }
  }

  private List<String> fetchLanguagesSafe(String repoName) {
    try {return gitHubClient.getRepoLanguages(repoName);}
    catch (Exception e) {
      log.warn("  Could not fetch languages for {}: {}", repoName, e.getMessage());
      return List.of();
    }
  }

  public Set<String> getLastSkippedRepos() {return lastSkippedRepos;}

  public void syncRepoStats() {
    try {
      Map<String, String> linkedRepos = findAllLinkedRepos();
      if (linkedRepos.isEmpty()) {
        log.info("No repos linked to a cc:Project — run importRepos first");
        return;
      }
      gitHubClient.clearTimedOutRepos();

      log.info("Fetching contributor stats for {} linked repos...", linkedRepos.size());
      Map<String, RepoStats> repoStatsMap = fetchRepoStats(linkedRepos.keySet());

      Map<String, String> personsByLogin = findAllPersonsWithGithubLogin();
      Map<String, String> slackIdByLogin = gitHubRmlService.buildGithubLoginToSlackId();
      Set<String> committerLogins = repoStatsMap.values()
                                                .stream()
                                                .flatMap(s -> s.commits()
                                                               .keySet()
                                                               .stream())
                                                .filter(personsByLogin::containsKey)
                                                .collect(Collectors.toCollection(LinkedHashSet::new));
      log.info("Fetching review and merged PR counts for {} committers (of {} linked persons, search API is 30 req/min)...",
        committerLogins.size(), personsByLogin.size());
      Map<String, SearchResult> reviewResultsByLogin = fetchByPerson(committerLogins, gitHubClient::getReviewCountsByRepoAsync);
      Map<String, SearchResult> mergedResultsByLogin = fetchByPerson(committerLogins, gitHubClient::getMergedPrCountsByRepoAsync);
      Map<String, Map<String, Integer>> reviewsByLogin = countsByLogin(reviewResultsByLogin);
      Map<String, Map<String, Integer>> mergedByLogin = countsByLogin(mergedResultsByLogin);

      int created = 0, updated = 0;
      int repoIndex = 0;
      for (Map.Entry<String, String> repoEntry : linkedRepos.entrySet()) {
        String repoName = repoEntry.getKey();
        String projectUri = repoEntry.getValue();
        repoIndex++;

        RepoStats stats = repoStatsMap.getOrDefault(repoName, RepoStats.empty(repoName));
        Set<String> logins = gatherLogins(stats.commits(), reviewsByLogin, mergedByLogin, repoName);
        log.info("  [{}/{}] {} — {} contributors", repoIndex, linkedRepos.size(), repoName, logins.size());

        for (String login : logins) {
          String personUri = personsByLogin.get(login);
          if (personUri == null) {
            String slackId = slackIdByLogin.get(login.toLowerCase());
            if (slackId != null) personUri = findPersonBySlackId(slackId);
          }
          if (personUri == null) {
            log.info("  No cc:Person for login {} — skipping", login);
            continue;
          }
          Activity activity = buildActivity(login, repoName, stats,
            reviewsByLogin, mergedByLogin, reviewResultsByLogin, mergedResultsByLogin);
          if (upsertContribution(login, activity, personUri, projectUri, repoName)) created++;
          else updated++;
        }
      }

      lastSkippedRepos = Set.copyOf(gitHubClient.getTimedOutRepos());
      if (lastSkippedRepos.isEmpty()) {
        log.info("Repo sync complete: {} repos, {} contributions created, {} updated",
          linkedRepos.size(), created, updated);
      }
      else {
        log.warn("Repo sync complete: {} repos, {} created, {} updated — {} repos skipped (stats not ready): {}",
          linkedRepos.size(), created, updated, lastSkippedRepos.size(), lastSkippedRepos);
        log.warn("Re-run 'Stats' sync to retry the skipped repos.");
      }
    }
    catch (Exception e) {
      log.error("Repo sync failed: {}", e.getMessage(), e);
    }
  }

  private static Set<String> gatherLogins(Map<String, Integer> commits,
                                          Map<String, Map<String, Integer>> reviewsByLogin,
                                          Map<String, Map<String, Integer>> mergedByLogin,
                                          String repoName) {
    Set<String> logins = new HashSet<>(commits.keySet());
    reviewsByLogin.forEach((login, byRepo) -> {if (byRepo.containsKey(repoName)) logins.add(login);});
    mergedByLogin.forEach((login, byRepo) -> {if (byRepo.containsKey(repoName)) logins.add(login);});
    return logins;
  }

  private static Activity buildActivity(String login, String repoName, RepoStats stats,
                                        Map<String, Map<String, Integer>> reviewsByLogin,
                                        Map<String, Map<String, Integer>> mergedByLogin,
                                        Map<String, SearchResult> reviewResults,
                                        Map<String, SearchResult> mergedResults) {
    int commits = stats.commits()
                       .getOrDefault(login, 0);
    int reviews = reviewsByLogin.getOrDefault(login, Map.of())
                                .getOrDefault(repoName, 0);
    int mergedPrs = mergedByLogin.getOrDefault(login, Map.of())
                                 .getOrDefault(repoName, 0);
    int linesAdded = stats.linesAdded()
                          .getOrDefault(login, 0);
    int linesDeleted = stats.linesDeleted()
                            .getOrDefault(login, 0);
    String firstCommit = stats.firstDates()
                              .get(login);
    String lastCommit = stats.lastDates()
                             .get(login);
    String fallback = firstCommit == null ? earliestSearchDate(login, reviewResults, mergedResults) : null;
    return new Activity(commits, reviews, mergedPrs, firstCommit, lastCommit, fallback, linesAdded, linesDeleted);
  }

  private static String earliestSearchDate(String login,
                                           Map<String, SearchResult> reviewResults,
                                           Map<String, SearchResult> mergedResults) {
    String r = reviewResults.containsKey(login) ? reviewResults.get(login)
                                                               .earliestDate() : null;
    String m = mergedResults.containsKey(login) ? mergedResults.get(login)
                                                               .earliestDate() : null;
    if (r != null && m != null) return r.compareTo(m) < 0 ? r : m;
    return r != null ? r : m;
  }

  private static Map<String, Map<String, Integer>> countsByLogin(Map<String, SearchResult> byLogin) {
    return byLogin.entrySet()
                  .stream()
                  .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue()
                                                                     .countsByRepo()));
  }

  private Map<String, RepoStats> fetchRepoStats(Collection<String> repos) {
    log.info("  Fetching contributor stats for {} repos in parallel (retries on 202)...", repos.size());
    Map<String, CompletableFuture<List<GitHubClient.ContributorStat>>> futures = new LinkedHashMap<>();
    for (String repo : repos) futures.put(repo, gitHubClient.getContributorStatsAsync(repo));

    Map<String, RepoStats> result = new LinkedHashMap<>();
    futures.forEach((repo, future) -> result.put(repo, collectStats(repo, future.join())));
    return result;
  }

  private static RepoStats collectStats(String repo, List<GitHubClient.ContributorStat> stats) {
    Map<String, Integer> commits = new HashMap<>();
    Map<String, String> firstDates = new HashMap<>();
    Map<String, String> lastDates = new HashMap<>();
    Map<String, Integer> linesAdded = new HashMap<>();
    Map<String, Integer> linesDeleted = new HashMap<>();
    for (GitHubClient.ContributorStat s : stats) {
      commits.put(s.login(), s.totalCommits());
      if (s.firstCommitDate() != null) firstDates.put(s.login(), s.firstCommitDate());
      if (s.lastCommitDate() != null) lastDates.put(s.login(), s.lastCommitDate());
      linesAdded.put(s.login(), s.linesAdded());
      linesDeleted.put(s.login(), s.linesDeleted());
    }
    return new RepoStats(repo, commits, firstDates, lastDates, linesAdded, linesDeleted);
  }

  private Map<String, SearchResult> fetchByPerson(Collection<String> logins,
                                                  Function<String, CompletableFuture<SearchResult>> fetcher) {
    Map<String, SearchResult> result = new LinkedHashMap<>();
    for (String login : logins) {
      result.put(login, fetcher.apply(login)
                               .join());
      sleepQuietly(SEARCH_API_DELAY_MS);
    }
    return result;
  }

  private boolean upsertContribution(String login, Activity act, String personUri, String projectUri, String repo) {
    String existing = findContribution(personUri, projectUri);
    String startDate = act.firstCommitDate() != null ? act.firstCommitDate() : act.fallbackDate();
    if (existing == null) {
      gitHubRmlService.createContribution(new GitHubRmlService.ContributionData(
        contributionId(personUri, projectUri), personUri, projectUri, GitHubRmlService.DEFAULT_CONTRIBUTION_ROLE,
        act.commits(), act.reviews(), act.mergedPrs(), startDate, act.lastCommitDate(),
        act.linesAdded(), act.linesDeleted()));
      log.info("  Created contribution for {} on {}", login, repo);
      return true;
    }
    gitHubRmlService.updateContributionStats(existing, act.commits(), act.reviews(), act.mergedPrs(),
      startDate, act.lastCommitDate(), act.linesAdded(), act.linesDeleted());
    return false;
  }

  private static String contributionId(String personUri, String projectUri) {
    return UriUtils.sanitizeForUri(UriUtils.extractLocalName(personUri) + "-" + UriUtils.extractLocalName(projectUri));
  }

  private static void sleepQuietly(long ms) {
    try {Thread.sleep(ms);}
    catch (InterruptedException e) {
      Thread.currentThread()
            .interrupt();
    }
  }

  public void upsertClientEngagements() {
    String cognizoneUri = readFirstUri(SparqlQueries.findOwnOrganization(), "org");
    if (cognizoneUri == null) {
      log.info("Skipping client engagement upsert — no Slack-sourced organization found (run Slack sync first)");
      return;
    }
    log.info("Upserting client engagements for {}...", cognizoneUri);

    List<Map.Entry<String, String>> rows = rdfRepository.read(model ->
                                                          SparqlExecutor.select(model, SparqlQueries.findClientOrgsWithEarliestDate(), row -> {
                                                            String clientOrg = SparqlExecutor.getUri(row, "clientOrg");
                                                            String startDate = SparqlExecutor.getString(row, "startDate");
                                                            return clientOrg != null && startDate != null ? Map.entry(clientOrg, startDate) : null;
                                                          }));

    log.info("  {} client orgs found", rows.size());
    for (Map.Entry<String, String> row : rows) {
      upsertEngagement(cognizoneUri, row.getKey(), row.getValue());
    }
  }

  private void upsertEngagement(String cognizoneUri, String clientOrgUri, String startDate) {
    String clientLocal = clientOrgUri.substring(clientOrgUri.lastIndexOf('/') + 1);
    String engagementUri = Namespaces.DATA + "Engagement/" + clientLocal;
    rdfRepository.write(model -> {
      Resource engagement = model.createResource(engagementUri);
      if (!model.containsResource(engagement)) {
        createEngagementStub(model, engagement, cognizoneUri, clientOrgUri, clientLocal);
      }
      setDate(model, engagement, Namespaces.SCHEMA + "startDate", startDate.substring(0, 10));
    });
  }

  private static void createEngagementStub(Model model, Resource engagement,
                                           String cognizoneUri, String clientOrgUri, String clientLocal) {
    model.add(engagement, RDF.type, model.createResource(Namespaces.CC + "Engagement"));
    model.add(engagement, model.createProperty(Namespaces.CC + "engagementOf"), model.createResource(cognizoneUri));
    model.add(engagement, model.createProperty(Namespaces.CC + "engagementWith"), model.createResource(clientOrgUri));
    model.add(engagement, model.createProperty(Namespaces.CC + "engagementType"), model.createResource(Namespaces.CCV_ENGAGEMENT_CLIENT));
    model.add(engagement, model.createProperty(Namespaces.CC + "engagementStatus"), model.createResource(Namespaces.CCV_ENGAGEMENT_ACTIVE));
    model.add(engagement, model.createProperty(Namespaces.DCT + "source"), model.createResource(Namespaces.CCV_GITHUB));
    model.add(model.createResource(clientOrgUri), model.createProperty(Namespaces.CC + "hasEngagement"), engagement);
    log.info("  Created engagement for client {}", clientLocal);
  }

  public void inferPersonClientLinks() {
    log.info("Inferring cc:workedFor links from contribution data...");
    try {
      List<Map.Entry<String, String>> pairs = rdfRepository.read(model ->
                                                             SparqlExecutor.select(model, SparqlQueries.findPersonClientOrgs(), row -> {
                                                               String person = SparqlExecutor.getUri(row, "person");
                                                               String clientOrg = SparqlExecutor.getUri(row, "clientOrg");
                                                               return person != null && clientOrg != null ? Map.entry(person, clientOrg) : null;
                                                             }));

      rdfRepository.write(model -> {
        model.removeAll(null, model.createProperty(Namespaces.CC + "workedFor"), null);
        for (Map.Entry<String, String> pair : pairs) {
          model.add(model.createResource(pair.getKey()),
            model.createProperty(Namespaces.CC + "workedFor"),
            model.createResource(pair.getValue()));
        }
      });
      log.info("  Wrote {} cc:workedFor links across {} persons", pairs.size(),
        pairs.stream()
             .map(Map.Entry::getKey)
             .distinct()
             .count());
    }
    catch (Exception e) {
      log.error("Failed to infer workedFor links: {}", e.getMessage(), e);
    }
  }

  public void updateEngagementLifecycle() {
    log.info("Updating engagement lifecycle status...");
    try {
      LocalDate cutoff = LocalDate.now()
                                  .minusMonths(12);
      List<Map.Entry<String, String>> rows = rdfRepository.read(model ->
                                                            SparqlExecutor.select(model, SparqlQueries.findEngagementsForLifecycle(), row -> {
                                                              String engagement = SparqlExecutor.getUri(row, "engagement");
                                                              String lastActivity = SparqlExecutor.getString(row, "lastActivity");
                                                              return engagement != null ? Map.entry(engagement, lastActivity != null ? lastActivity : "") : null;
                                                            }));

      int completed = 0, active = 0;
      for (Map.Entry<String, String> row : rows) {
        boolean isStale = isStaleEngagement(row.getValue(), cutoff);
        applyEngagementLifecycle(row.getKey(), isStale, row.getValue());
        if (isStale) completed++;
        else active++;
      }
      log.info("  Lifecycle update: {} active, {} completed (cutoff: {})", active, completed, cutoff);
    }
    catch (Exception e) {
      log.error("Failed to update engagement lifecycle: {}", e.getMessage(), e);
    }
  }

  private static boolean isStaleEngagement(String lastActivity, LocalDate cutoff) {
    return !lastActivity.isEmpty() && LocalDate.parse(lastActivity.substring(0, 10))
                                               .isBefore(cutoff);
  }

  private void applyEngagementLifecycle(String engagementUri, boolean isStale, String lastActivity) {
    String status = isStale ? Namespaces.CCV_ENGAGEMENT_COMPLETED : Namespaces.CCV_ENGAGEMENT_ACTIVE;
    rdfRepository.write(model -> {
      Resource engagement = model.createResource(engagementUri);
      setResource(model, engagement, Namespaces.CC + "engagementStatus", status);
      setDate(model, engagement, Namespaces.SCHEMA + "endDate", isStale ? lastActivity.substring(0, 10) : null);
    });
  }

  public void updateProjectStatus() {
    log.info("Deriving project status from archive flag + last activity...");
    try {
      LocalDate cutoff = LocalDate.now().minusMonths(12);
      Map<String, String> statuses = new LinkedHashMap<>();
      rdfRepository.read(model ->
        SparqlExecutor.select(model, SparqlQueries.findProjectsForStatus(), row -> {
          String project = SparqlExecutor.getUri(row, "project");
          if (project == null) return null;
          boolean archived = SparqlExecutor.getBoolean(row, "archived", false);
          String lastActivity = SparqlExecutor.getString(row, "lastActivity");
          statuses.put(project, deriveProjectStatus(archived, lastActivity, cutoff));
          return project;
        }));

      rdfRepository.write(model -> statuses.forEach((uri, status) ->
        setResource(model, model.createResource(uri), Namespaces.CC + "projectStatus", status)));

      long ongoing = statuses.values().stream().filter(Namespaces.CCV_PROJECT_ONGOING::equals).count();
      long completed = statuses.values().stream().filter(Namespaces.CCV_PROJECT_COMPLETED::equals).count();
      long paused = statuses.values().stream().filter(Namespaces.CCV_PROJECT_PAUSED::equals).count();
      log.info("  Project status: {} ongoing, {} completed, {} paused (cutoff: {})", ongoing, completed, paused, cutoff);
    }
    catch (Exception e) {
      log.error("Failed to derive project status: {}", e.getMessage(), e);
    }
  }

  private static String deriveProjectStatus(boolean archived, String lastActivity, LocalDate cutoff) {
    if (archived) return Namespaces.CCV_PROJECT_COMPLETED;
    if (lastActivity == null || lastActivity.isEmpty()) return Namespaces.CCV_PROJECT_ONGOING;
    boolean stale = LocalDate.parse(lastActivity.substring(0, 10)).isBefore(cutoff);
    return stale ? Namespaces.CCV_PROJECT_PAUSED : Namespaces.CCV_PROJECT_ONGOING;
  }

  private static final Set<String> CURATED_AVAILABILITY = Set.of(
    Namespaces.CCV_ON_LEAVE, Namespaces.CCV_PARTIALLY_AVAILABLE, Namespaces.CCV_NOT_AVAILABLE);

  public void deriveAvailability() {
    log.info("Deriving person availability from recent contributions...");
    try {
      LocalDate cutoff = LocalDate.now().minusMonths(12);
      Map<String, String> updates = new LinkedHashMap<>();
      rdfRepository.read(model ->
        SparqlExecutor.select(model, SparqlQueries.findPersonsForAvailability(), row -> {
          String person = SparqlExecutor.getUri(row, "person");
          if (person == null) return null;
          if (CURATED_AVAILABILITY.contains(SparqlExecutor.getUri(row, "status"))) return person;
          String lastActivity = SparqlExecutor.getString(row, "lastActivity");
          boolean onProject = lastActivity != null && !lastActivity.isEmpty()
                              && !LocalDate.parse(lastActivity.substring(0, 10)).isBefore(cutoff);
          updates.put(person, onProject ? Namespaces.CCV_ON_PROJECT : Namespaces.CCV_AVAILABLE);
          return person;
        }));

      rdfRepository.write(model -> updates.forEach((uri, status) ->
        setResource(model, model.createResource(uri), Namespaces.CC + "availabilityStatus", status)));

      long onProject = updates.values().stream().filter(Namespaces.CCV_ON_PROJECT::equals).count();
      long available = updates.size() - onProject;
      log.info("  Availability: {} on project, {} available (curated statuses left untouched) (cutoff: {})",
               onProject, available, cutoff);
    }
    catch (Exception e) {
      log.error("Failed to derive availability: {}", e.getMessage(), e);
    }
  }

  private void linkToClient(String projectUri, String repoName) {
    int dash = repoName.indexOf('-');
    if (dash <= 0) return;
    String prefix = repoName.substring(0, dash);
    if (isInternal(repoName) || isInternal(prefix)) return;
    String orgUri = prefixMap().getOrDefault(prefix, Namespaces.DATA + "Organization/" + prefix);
    rdfRepository.write(model -> {
      Resource client = ensureClientOrg(model, prefix, orgUri);
      Resource project = model.createResource(projectUri);
      model.removeAll(project, model.createProperty(Namespaces.CC + "requestedBy"), null);
      model.add(project, model.createProperty(Namespaces.CC + "requestedBy"), client);
    });
    log.info("  Linked {} → client org {}", repoName, orgUri);
  }

  private static Resource ensureClientOrg(Model model, String prefix, String uri) {
    Resource client = model.createResource(uri);
    if (!model.containsResource(client)) {
      model.add(client, RDF.type, model.createResource(Namespaces.CC + "Organization"));
      model.add(client, model.createProperty(Namespaces.DCT + "identifier"), prefix);
      model.add(client, model.createProperty(Namespaces.DCT + "source"), model.createResource(Namespaces.CCV_GITHUB));
      log.info("  Created client org stub with code '{}': {}", prefix, uri);
    }
    return client;
  }

  private boolean isInternal(String repoName) {return internalRepos().contains(repoName);}

  private Map<String, String> prefixMap() {
    if (cachedPrefixMap == null) cachedPrefixMap = gitHubRmlService.buildPrefixMap();
    return cachedPrefixMap;
  }

  private List<String> internalRepos() {
    if (cachedInternalRepos == null) cachedInternalRepos = gitHubRmlService.getInternalRepos();
    return cachedInternalRepos;
  }

  private String readFirstUri(String query, String var) {
    List<String> results = rdfRepository.read(model ->
      SparqlExecutor.select(model, query, row -> SparqlExecutor.getUri(row, var)));
    return results.isEmpty() ? null : results.get(0);
  }

  private Map<String, String> readAsMap(String query, Function<QuerySolution, Map.Entry<String, String>> mapper) {
    return rdfRepository.read(model -> SparqlExecutor.select(model, query, mapper))
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
  }

  private Map<String, String> buildPersonReconcileIndex() {
    return rdfRepository.read(model -> {
      Map<String, String> index = new HashMap<>();
      model.listSubjectsWithProperty(RDF.type, model.createResource(Namespaces.CC + "Person")).forEach(person -> {
        String uri = person.getURI();
        indexNormalized(index, person.getProperty(model.createProperty(Namespaces.FOAF + "nick")), uri);
        AdmsIdentifiers.notation(person, Namespaces.CCV_GITHUB).ifPresent(gh -> {
          String key = normalize(gh);
          if (!key.isEmpty()) index.putIfAbsent(key, uri);
        });
        Statement given = person.getProperty(model.createProperty(Namespaces.SCHEMA + "givenName"));
        Statement family = person.getProperty(model.createProperty(Namespaces.SCHEMA + "familyName"));
        if (given != null && family != null) {
          String key = normalize(given.getString() + family.getString());
          if (!key.isEmpty()) index.putIfAbsent(key, uri);
        }
      });
      return index;
    });
  }

  private static void indexNormalized(Map<String, String> index, Statement stmt, String uri) {
    if (stmt == null) return;
    String key = normalize(stmt.getString());
    if (!key.isEmpty()) index.putIfAbsent(key, uri);
  }

  private static String matchExistingPerson(UserProfile profile, Map<String, String> index) {
    for (String key : reconcileKeys(profile)) {
      String hit = index.get(key);
      if (hit != null) return hit;
    }
    return null;
  }

  private static List<String> reconcileKeys(UserProfile profile) {
    List<String> keys = new ArrayList<>();
    addKey(keys, normalize(profile.login()));
    addKey(keys, normalizeLogin(profile.login()));
    addKey(keys, normalize(profile.name()));
    return keys;
  }

  private static void addKey(List<String> keys, String key) {
    if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  private static final List<String> LOGIN_SUFFIXES = List.of("cognizone", "cogni", "cz", "zone");

  private static String normalizeLogin(String login) {
    String value = normalize(login).replaceAll("[0-9]+$", "");
    for (String suffix : LOGIN_SUFFIXES) {
      if (value.length() > suffix.length() + 2 && value.endsWith(suffix)) {
        return value.substring(0, value.length() - suffix.length());
      }
    }
    return value;
  }

  private Map<String, String> findAllPersonsWithGithubLogin() {
    return readAsMap(SparqlQueries.findAllPersonsWithGithubUsername(), row -> {
      String uri = SparqlExecutor.getUri(row, "person");
      String username = SparqlExecutor.getString(row, "username");
      return (uri != null && username != null) ? Map.entry(username, uri) : null;
    });
  }

  private Map<String, String> findAllLinkedRepos() {
    String orgBaseUrl = GITHUB_BASE + org + "/";
    return readAsMap(SparqlQueries.findAllLinkedRepositories(orgBaseUrl), row -> {
      String repoUrl = SparqlExecutor.getString(row, "repoUrl");
      String projectUri = SparqlExecutor.getUri(row, "project");
      if (repoUrl == null || projectUri == null) return null;
      return Map.entry(repoUrl.substring(orgBaseUrl.length()), projectUri);
    });
  }

  private String findProjectByRepoUrl(String repoUrl) {
    return readFirstUri(SparqlQueries.findProjectByRepositoryUrl(repoUrl), "project");
  }

  private String findPersonByGithubLogin(String login) {
    return readFirstUri(SparqlQueries.findPersonByGithubLogin(login), "person");
  }

  private String findPersonBySlackId(String slackId) {
    return readFirstUri(SparqlQueries.findPersonBySlackId(slackId), "person");
  }

  private String findContribution(String personUri, String projectUri) {
    return readFirstUri(SparqlQueries.findContributionByPersonAndProject(personUri, projectUri), "contribution");
  }
}
