package zone.cogni.companycard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class GitHubClient {
    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String BASE_URL = "https://api.github.com";

    private static final int MAX_CONCURRENT_REQUESTS = 10;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();

    private final Semaphore concurrencyLimit = new Semaphore(MAX_CONCURRENT_REQUESTS);

    private final Set<String> timedOutRepos = ConcurrentHashMap.newKeySet();

    public Set<String> getTimedOutRepos() {
        return Collections.unmodifiableSet(timedOutRepos);
    }

    public void clearTimedOutRepos() {
        timedOutRepos.clear();
    }

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Value("${app.github.token:}")
    private String token;

    @Value("${app.github.org:cognizone}")
    private String org;

    private record Repo(String name, String description, String htmlUrl, boolean fork, String createdAt,
                        String pushedAt, boolean archived, List<String> topics) {}

    private record Member(String login, String type) {}

    private record ContributorWeek(long w, int a, int d, int c) {}

    private record ContributorStatRaw(UserProfile author, int total, List<ContributorWeek> weeks) {}

    private record RawResponse(String body, String linkHeader) {}

    public record ContributorStat(String login, int totalCommits, String firstCommitDate, String lastCommitDate,
                                  int linesAdded, int linesDeleted) {}

    public record RepoInfo(String name, String description, String htmlUrl, boolean fork, String createdAt,
                           String pushedAt, boolean archived, List<String> topics) {}

    public record SearchResult(Map<String, Integer> countsByRepo, String earliestDate) {}

    public record UserProfile(String login, String name, String avatarUrl, String bio, String email, String location) {
        @JsonProperty
        public String givenName() {
            return nameParts()[0];
        }

        @JsonProperty
        public String familyName() {
            return nameParts()[1];
        }

        private String[] nameParts() {
            if (!StringUtils.hasText(name)) return new String[]{null, null};
            int space = name.indexOf(' ');
            return space > 0 ? new String[]{name.substring(0, space), name.substring(space + 1).trim()} : new String[]{name.trim(), null};
        }
    }

    public List<String> getRepoLanguages(String repo) throws Exception {
        HttpRequest request = buildRequestFromUrl(BASE_URL + "/repos/" + org + "/" + repo + "/languages");
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204 || !StringUtils.hasText(response.body())) return List.of();
        if (response.statusCode() != 200)
            throw new IllegalStateException("HTTP " + response.statusCode() + " fetching languages for " + repo);
        return new ArrayList<>(readObjectMap(response.body()).keySet());
    }

    public List<RepoInfo> getRepos() throws Exception {
        return fetchListSync("/orgs/" + org + "/repos?per_page=100&type=all", Repo.class).stream().filter(r -> !r.fork()).map(r -> new RepoInfo(r.name(), r.description(), r.htmlUrl(), r.fork(), toDateString(r.createdAt()), toDateString(r.pushedAt()), r.archived(), r.topics() != null ? r.topics() : List.of())).toList();
    }

    public List<String> getOrgMemberLogins() throws Exception {
        return fetchListSync("/orgs/" + org + "/members?per_page=100", Member.class).stream().filter(m -> !"Bot".equals(m.type())).map(Member::login).toList();
    }

    private static final ScheduledExecutorService STATS_RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stats-retry");
        t.setDaemon(true);
        return t;
    });
    private static final int STATS_MAX_RETRIES = 12;
    private static final long STATS_RETRY_DELAY_MS = 10_000;

    public CompletableFuture<List<ContributorStat>> getContributorStatsAsync(String repo) {
        return fetchContributorStats(repo, STATS_MAX_RETRIES);
    }

    private CompletableFuture<List<ContributorStat>> fetchContributorStats(String repo, int retriesLeft) {
        return getRawFull(BASE_URL + "/repos/" + org + "/" + repo + "/stats/contributors").thenApply(resp -> parseContributorStats(repo, resp.body())).exceptionallyCompose(ex -> retryStatsOrEmpty(repo, ex, retriesLeft));
    }

    private CompletableFuture<List<ContributorStat>> retryStatsOrEmpty(String repo, Throwable ex, int retriesLeft) {
        boolean is202 = ex.getMessage() != null && ex.getMessage().contains("202");
        if (is202 && retriesLeft > 0) {
            log.debug("Contributor stats not ready for {} (202) — retrying in {}s ({} retries left)", repo, STATS_RETRY_DELAY_MS / 1000, retriesLeft);
            return CompletableFuture.supplyAsync(() -> fetchContributorStats(repo, retriesLeft - 1), CompletableFuture.delayedExecutor(STATS_RETRY_DELAY_MS, TimeUnit.MILLISECONDS, STATS_RETRY_SCHEDULER)).thenCompose(f -> f);
        }
        if (is202) {
            log.warn("Contributor stats not ready for {} — giving up (will retry on next Stats sync)", repo);
            timedOutRepos.add(repo);
        }
        else log.warn("Could not fetch contributor stats for {}: {}", repo, ex.getMessage());
        return CompletableFuture.completedFuture(List.of());
    }

    private List<ContributorStat> parseContributorStats(String repo, String body) {
        if (body == null) return List.of();
        try {
            return readList(body, ContributorStatRaw.class).stream().filter(s -> s.author() != null && s.total() > 0).map(GitHubClient::toContributorStat).toList();
        } catch (Exception e) {
            log.warn("Could not parse contributor stats for {}: {}", repo, e.getMessage());
            return List.of();
        }
    }

    private static ContributorStat toContributorStat(ContributorStatRaw s) {
        List<ContributorWeek> activeWeeks = s.weeks().stream().filter(w -> w.c() > 0).toList();
        String firstDate = activeWeeks.isEmpty() ? null : epochDay(activeWeeks.get(0).w());
        String lastDate = activeWeeks.isEmpty() ? null : epochDay(activeWeeks.get(activeWeeks.size() - 1).w());
        int linesAdded = s.weeks().stream().mapToInt(ContributorWeek::a).sum();
        int linesDeleted = s.weeks().stream().mapToInt(ContributorWeek::d).sum();
        return new ContributorStat(s.author().login(), s.total(), firstDate, lastDate, linesAdded, linesDeleted);
    }

    private static String epochDay(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    public CompletableFuture<SearchResult> getMergedPrCountsByRepoAsync(String login) {
        return searchIssuesByRepo("/search/issues?q=is:pr+is:merged+org:" + org + "+author:" + login + "&per_page=100", login);
    }

    public CompletableFuture<SearchResult> getReviewCountsByRepoAsync(String login) {
        return searchIssuesByRepo("/search/issues?q=is:pr+org:" + org + "+reviewed-by:" + login + "&per_page=100", login);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SearchResult> searchIssuesByRepo(String path, String login) {
        return getRawFull(BASE_URL + path).thenApply(resp -> {
            if (resp.body() == null) return new SearchResult(Map.of(), null);
            try {
                Map<String, Object> root = readObjectMap(resp.body());
                List<Map<String, Object>> items = (List<Map<String, Object>>) root.getOrDefault("items", List.of());
                Map<String, Integer> countsByRepo = items.stream().map(item -> (String) item.get("repository_url")).filter(url -> url != null).collect(Collectors.groupingBy(url -> url.substring(url.lastIndexOf('/') + 1), Collectors.summingInt(url -> 1)));
                String earliestDate = items.stream().map(item -> (String) item.get("created_at")).filter(d -> d != null && d.length() >= 10).map(d -> d.substring(0, 10)).min(String::compareTo).orElse(null);
                return new SearchResult(countsByRepo, earliestDate);
            } catch (Exception e) {
                log.warn("Could not parse search results for {}: {}", login, e.getMessage());
                return new SearchResult(Map.of(), null);
            }
        }).exceptionally(e -> {
            if (e.getMessage() != null && e.getMessage().contains("422"))
                log.debug("Skipping search for {} — GitHub user not found (422)", login);
            else log.warn("Could not fetch search results for {}: {}", login, e.getMessage());
            return new SearchResult(Map.of(), null);
        });
    }

    public CompletableFuture<UserProfile> getUserProfileAsync(String login) {
        return fetchAsync("/users/" + login, UserProfile.class).exceptionally(e -> {
            log.warn("Could not fetch profile for {}: {}", login, e.getMessage());
            return null;
        });
    }

    public String getLastCommitDate(String repo) {
        try {
            HttpRequest request = buildRequestFromUrl(BASE_URL + "/repos/" + org + "/" + repo + "/commits?per_page=1");
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409 || response.statusCode() == 204) return null;
            if (response.statusCode() != 200) {
                log.warn("Could not fetch last commit for {}: HTTP {}", repo, response.statusCode());
                return null;
            }
            JsonNode commits = mapper.readTree(response.body());
            if (!commits.isArray() || commits.isEmpty()) return null;
            JsonNode date = commits.get(0).path("commit").path("committer").path("date");
            return date.isMissingNode() ? null : toDateString(date.asText(null));
        } catch (Exception e) {
            log.warn("Could not fetch last commit date for {}: {}", repo, e.getMessage());
            return null;
        }
    }

    private static String toDateString(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.length() < 10) return null;
        return isoDateTime.substring(0, 10);
    }

    private <T> List<T> fetchListSync(String path, Class<T> type) throws Exception {
        List<T> all = new ArrayList<>();
        String nextUrl = BASE_URL + path;
        while (nextUrl != null) {
            HttpRequest request = buildRequestFromUrl(nextUrl);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) break;
            if (response.statusCode() != 200)
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + nextUrl);
            all.addAll(readList(response.body(), type));
            nextUrl = extractNextUrl(response.headers().firstValue("Link").orElse(null));
        }
        return all;
    }

    private <T> CompletableFuture<T> fetchAsync(String path, Class<T> type) {
        return getRawFull(BASE_URL + path).thenApply(resp -> {
            if (resp.body() == null) return null;
            try {
                return mapper.readValue(resp.body(), type);
            } catch (Exception e) {
                log.warn("Failed to deserialize object from {}: {}", path, e.getMessage());
                return null;
            }
        });
    }

    private <T> List<T> readList(String body, Class<T> type) throws JsonProcessingException {
        return mapper.readValue(body, mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private Map<String, Object> readObjectMap(String body) throws JsonProcessingException {
        return mapper.readValue(body, mapper.getTypeFactory().constructMapLikeType(LinkedHashMap.class, String.class, Object.class));
    }

    private CompletableFuture<RawResponse> getRawFull(String url) {
        HttpRequest request;
        try {
            request = buildRequestFromUrl(url);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        concurrencyLimit.acquireUninterruptibly();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((r, t) -> concurrencyLimit.release()).thenApply(response -> switch (response.statusCode()) {
            case 200 -> new RawResponse(response.body(), response.headers().firstValue("Link").orElse(null));
            case 204 -> new RawResponse(null, null);
            default -> throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
        });
    }

    private HttpRequest buildRequestFromUrl(String url) {
        if (!StringUtils.hasText(token)) throw new IllegalStateException("app.github.token is not configured");
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/vnd.github+json").header("Authorization", "Bearer " + token).header("X-GitHub-Api-Version", "2022-11-28").GET().build();
    }

    private static String extractNextUrl(String linkHeader) {
        if (!StringUtils.hasText(linkHeader)) return null;
        for (String part : linkHeader.split(",")) {
            String[] segments = part.trim().split(";");
            if (segments.length == 2 && segments[1].trim().equals("rel=\"next\"")) {
                String url = segments[0].trim();
                return url.substring(1, url.length() - 1);
            }
        }
        return null;
    }
}
