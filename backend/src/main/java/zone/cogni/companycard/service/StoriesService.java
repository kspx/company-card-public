package zone.cogni.companycard.service;

import org.apache.jena.query.QuerySolution;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class StoriesService {
    private final RdfRepository rdfRepository;

    public StoriesService(RdfRepository rdfRepository) {
        this.rdfRepository = rdfRepository;
    }

    public record TopContributorRow(
        String personUri, String personName,
        int totalCommits, int totalLinesAdded, int totalMergedPrs, int totalReviews, int projectCount
    ) {}

    public record ClientVersatilityRow(
        String personUri, String personName,
        int clientCount, int projectCount, String clients
    ) {}

    public record EmployeeJourneyRow(
        String personUri, String personName,
        String projectUri, String projectName,
        String clientUri, String clientName,
        String startDate, String endDate,
        int commitCount
    ) {}

    public record DeliveryRiskRow(
        String orgUri, String clientName, String discipline,
        String personUri, String personName,
        int commitCount
    ) {}

    public record MeetTheTeamRow(
        String orgUri, String clientName,
        String personUri, String personName,
        int commitCount, int reviewCount, int projectCount,
        String firstActivity, String lastActivity
    ) {}

    public List<TopContributorRow> topContributors() {
        return rdfRepository.read(model ->
            SparqlExecutor.select(model, SparqlQueries.storyTopContributors(), row -> new TopContributorRow(
                SparqlExecutor.getUri(row, "person"), buildName(row),
                SparqlExecutor.getInt(row, "totalCommits", 0),
                SparqlExecutor.getInt(row, "totalLinesAdded", 0),
                SparqlExecutor.getInt(row, "totalMergedPrs", 0),
                SparqlExecutor.getInt(row, "totalReviews", 0),
                SparqlExecutor.getInt(row, "projectCount", 0)
            ))
        );
    }

    public List<ClientVersatilityRow> crossClientVersatility() {
        return rdfRepository.read(model ->
            SparqlExecutor.select(model, SparqlQueries.storyCrossClientVersatility(), row -> new ClientVersatilityRow(
                SparqlExecutor.getUri(row, "person"), buildName(row),
                SparqlExecutor.getInt(row, "clientCount", 0),
                SparqlExecutor.getInt(row, "projectCount", 0),
                SparqlExecutor.getStringOrDefault(row, "clients", "")
            ))
        );
    }

    public List<EmployeeJourneyRow> employeeJourney() {
        return rdfRepository.read(model ->
            SparqlExecutor.select(model, SparqlQueries.storyEmployeeJourney(), row -> {
                String personUri  = SparqlExecutor.getUri(row, "person");
                String projectUri = SparqlExecutor.getUri(row, "project");
                return new EmployeeJourneyRow(
                    personUri, nameOrLocalName(row, "givenName", "familyName", personUri),
                    projectUri, SparqlExecutor.getStringOrDefault(row, "projectName", localName(projectUri)),
                    SparqlExecutor.getUri(row, "org"),
                    SparqlExecutor.getStringOrDefault(row, "clientName", "?"),
                    SparqlExecutor.getString(row, "startDate"),
                    SparqlExecutor.getString(row, "endDate"),
                    SparqlExecutor.getInt(row, "commitCount", 0)
                );
            })
        );
    }

    public List<DeliveryRiskRow> deliveryRisk() {
        return rdfRepository.read(model ->
            SparqlExecutor.select(model, SparqlQueries.storyDeliveryRisk(), row -> new DeliveryRiskRow(
                SparqlExecutor.getUri(row, "org"),
                SparqlExecutor.getStringOrDefault(row, "clientName", "?"),
                SparqlExecutor.getStringOrDefault(row, "discipline", "?"),
                SparqlExecutor.getUri(row, "person"), buildName(row),
                SparqlExecutor.getInt(row, "commitCount", 0)
            ))
        );
    }

    public List<MeetTheTeamRow> meetTheTeam() {
        return rdfRepository.read(model ->
            SparqlExecutor.select(model, SparqlQueries.storyMeetTheTeam(), row -> new MeetTheTeamRow(
                SparqlExecutor.getUri(row, "org"),
                SparqlExecutor.getStringOrDefault(row, "clientName", "?"),
                SparqlExecutor.getUri(row, "person"), buildName(row),
                SparqlExecutor.getInt(row, "commitCount", 0),
                SparqlExecutor.getInt(row, "reviewCount", 0),
                SparqlExecutor.getInt(row, "projectCount", 0),
                SparqlExecutor.getString(row, "firstActivity"),
                SparqlExecutor.getString(row, "lastActivity")
            ))
        );
    }

    private static String buildName(QuerySolution row) {
        return nameOrLocalName(row, "givenName", "familyName", SparqlExecutor.getUri(row, "person"));
    }

    private static String nameOrLocalName(QuerySolution row, String givenVar, String familyVar, String fallbackUri) {
        String name = buildFullName(SparqlExecutor.getString(row, givenVar), SparqlExecutor.getString(row, familyVar));
        return name.isBlank() ? localName(fallbackUri) : name;
    }

    private static String buildFullName(String givenName, String familyName) {
        return Stream.of(givenName, familyName).filter(StringUtils::hasText).collect(Collectors.joining(" "));
    }

    private static String localName(String uri) {
        if (uri == null) return "?";
        int cut = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('#'));
        return cut >= 0 && cut < uri.length() - 1 ? uri.substring(cut + 1) : uri;
    }
}
