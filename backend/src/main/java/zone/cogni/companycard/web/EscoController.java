package zone.cogni.companycard.web;

import org.springframework.web.bind.annotation.*;
import zone.cogni.companycard.model.Concept;
import zone.cogni.companycard.service.EscoService;

import java.util.List;

@RestController
@RequestMapping("/api/esco")
public class EscoController {
    private final EscoService escoService;

    public EscoController(EscoService escoService) {
        this.escoService = escoService;
    }

    @GetMapping("/skills")
    public List<Concept> searchSkills(
            @RequestParam("text") String text,
            @RequestParam(value = "language", defaultValue = "en") String language,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return escoService.searchSkills(text, language, limit);
    }

    @GetMapping("/skill")
    public Concept getSkillDetails(
            @RequestParam("uri") String uri,
            @RequestParam(value = "language", defaultValue = "en") String language) {
        return escoService.getSkillDetails(uri, language);
    }

    @GetMapping("/occupations")
    public List<Concept> searchOccupations(
            @RequestParam("text") String text,
            @RequestParam(value = "language", defaultValue = "en") String language,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return escoService.searchOccupations(text, language, limit);
    }

    @GetMapping("/occupation")
    public Concept getOccupationDetails(
            @RequestParam("uri") String uri,
            @RequestParam(value = "language", defaultValue = "en") String language) {
        return escoService.getOccupationDetails(uri, language);
    }
}
