package zone.cogni.companycard.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zone.cogni.companycard.service.DisciplineTeamService;
import zone.cogni.companycard.service.GitHubRmlService;
import zone.cogni.companycard.service.GitHubSyncService;
import zone.cogni.companycard.service.OfficeService;
import zone.cogni.companycard.service.SlackSyncService;
import zone.cogni.companycard.service.SyncCoordinator;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final GitHubSyncService gitHubSyncService;
    private final SlackSyncService slackSyncService;
    private final GitHubRmlService gitHubRmlService;
    private final DisciplineTeamService disciplineTeamService;
    private final OfficeService officeService;
    private final SyncCoordinator coordinator;

    public AdminController(GitHubSyncService gitHubSyncService, SlackSyncService slackSyncService,
                          GitHubRmlService gitHubRmlService, DisciplineTeamService disciplineTeamService,
                          OfficeService officeService, SyncCoordinator coordinator) {
        this.gitHubSyncService = gitHubSyncService;
        this.slackSyncService = slackSyncService;
        this.gitHubRmlService = gitHubRmlService;
        this.disciplineTeamService = disciplineTeamService;
        this.officeService = officeService;
        this.coordinator = coordinator;
    }

    @PostMapping("/sync/all")
    public ResponseEntity<?> syncAll() {
        return launch("full-sync", "Full sync started (seed → Slack → GitHub → teams)", () -> {
            gitHubRmlService.seedOrganizations();
            slackSyncService.syncNow();
            gitHubSyncService.syncNow();
            disciplineTeamService.applyClassification();
        });
    }

    @GetMapping("/sync/status")
    public ResponseEntity<?> syncStatus() {
        String active = coordinator.activeSync();
        return ResponseEntity.ok(Map.of("running", active != null, "active", active != null ? active : ""));
    }

    @PostMapping("/github/sync")
    public ResponseEntity<?> syncGitHub() {
        return launch("github-full", "Full GitHub sync started", gitHubSyncService::syncNow);
    }

    @PostMapping("/github/sync/members")
    public ResponseEntity<?> syncMembers() {
        return launch("github-members", "Member import started", gitHubSyncService::importOrgMembers);
    }

    @PostMapping("/github/sync/repos")
    public ResponseEntity<?> syncRepos() {
        return launch("github-repos", "Repo import started", gitHubSyncService::importRepos);
    }

    @PostMapping("/github/sync/stats")
    public ResponseEntity<?> syncStats() {
        return launch("github-stats", "Repo stats sync started", gitHubSyncService::syncRepoStats);
    }

    @GetMapping("/github/sync/stats/skipped")
    public ResponseEntity<?> getSkippedRepos() {
        return ResponseEntity.ok(Map.of("repos", gitHubSyncService.getLastSkippedRepos()));
    }

    @PostMapping("/github/sync/engagements")
    public ResponseEntity<?> syncEngagements() {
        return launch("github-engagements", "Client engagement upsert started", gitHubSyncService::upsertClientEngagements);
    }

    @PostMapping("/github/sync/workedfor")
    public ResponseEntity<?> syncWorkedFor() {
        return launch("github-workedfor", "Worked-for inference started", gitHubSyncService::inferPersonClientLinks);
    }

    @PostMapping("/github/sync/lifecycle")
    public ResponseEntity<?> syncLifecycle() {
        return launch("github-lifecycle", "Engagement lifecycle update started", gitHubSyncService::updateEngagementLifecycle);
    }

    @PostMapping("/github/sync/project-status")
    public ResponseEntity<?> syncProjectStatus() {
        return launch("github-project-status", "Project status derivation started", gitHubSyncService::updateProjectStatus);
    }

    @PostMapping("/github/sync/availability")
    public ResponseEntity<?> syncAvailability() {
        return launch("github-availability", "Availability derivation started", gitHubSyncService::deriveAvailability);
    }

    @PostMapping("/seed/organizations")
    public ResponseEntity<?> seedOrganizations() {
        return launch("seed-orgs", "Organisation seed started", gitHubRmlService::seedOrganizations);
    }

    @PostMapping("/offices/derive")
    public ResponseEntity<?> deriveOffices() {
        return launch("derive-offices", "Office derivation from Wikidata started", officeService::deriveFromWikidata);
    }

    @PostMapping("/slack/sync")
    public ResponseEntity<?> syncSlack() {
        return launch("slack", "Slack sync started", slackSyncService::syncNow);
    }

    @PostMapping("/teams/seed-disciplines")
    public ResponseEntity<?> seedDisciplineTeams() {
        if (coordinator.activeSync() != null) return syncConflict();
        return ResponseEntity.ok(Map.of("created", disciplineTeamService.seedDisciplineTeams()));
    }

    @PostMapping("/teams/apply-disciplines")
    public ResponseEntity<?> applyDisciplines() {
        if (coordinator.activeSync() != null) return syncConflict();
        return ResponseEntity.ok(disciplineTeamService.applyClassification());
    }

    private ResponseEntity<?> syncConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                             .body(Map.of("error", "A sync is already running: " + coordinator.activeSync()));
    }

    private ResponseEntity<?> launch(String label, String message, Runnable task) {
        return coordinator.tryRunAsync(label, task)
            ? ResponseEntity.accepted().body(Map.of("message", message))
            : ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "A sync is already running: " + coordinator.activeSync()));
    }
}
