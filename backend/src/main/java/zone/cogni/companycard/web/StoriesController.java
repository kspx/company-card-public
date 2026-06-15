package zone.cogni.companycard.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zone.cogni.companycard.service.StoriesService;

import java.util.List;

@RestController
@RequestMapping("/api/stories")
public class StoriesController {
    private final StoriesService storiesService;

    public StoriesController(StoriesService storiesService) {
        this.storiesService = storiesService;
    }

    @GetMapping("/top-contributors")
    public ResponseEntity<List<StoriesService.TopContributorRow>> topContributors() {
        return ResponseEntity.ok(storiesService.topContributors());
    }

    @GetMapping("/cross-client-versatility")
    public ResponseEntity<List<StoriesService.ClientVersatilityRow>> crossClientVersatility() {
        return ResponseEntity.ok(storiesService.crossClientVersatility());
    }

    @GetMapping("/employee-journey")
    public ResponseEntity<List<StoriesService.EmployeeJourneyRow>> employeeJourney() {
        return ResponseEntity.ok(storiesService.employeeJourney());
    }

    @GetMapping("/delivery-risk")
    public ResponseEntity<List<StoriesService.DeliveryRiskRow>> deliveryRisk() {
        return ResponseEntity.ok(storiesService.deliveryRisk());
    }

    @GetMapping("/meet-the-team")
    public ResponseEntity<List<StoriesService.MeetTheTeamRow>> meetTheTeam() {
        return ResponseEntity.ok(storiesService.meetTheTeam());
    }
}
