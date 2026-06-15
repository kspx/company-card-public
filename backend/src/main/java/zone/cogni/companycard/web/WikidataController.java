package zone.cogni.companycard.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zone.cogni.companycard.service.WikidataEnrichmentService;
import zone.cogni.companycard.service.WikidataEnrichmentService.Candidate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wikidata")
public class WikidataController {
  private final WikidataEnrichmentService wikidata;

  public WikidataController(WikidataEnrichmentService wikidata) {
    this.wikidata = wikidata;
  }

  @GetMapping("/search")
  public List<Candidate> search(@RequestParam("q") String q,
                                @RequestParam(value = "limit", defaultValue = "10") int limit) {
    return wikidata.search(q, limit);
  }

  @GetMapping("/enrich")
  public Map<String, Object> enrich(@RequestParam("qid") String qid) {
    return wikidata.fetch(qid);
  }
}
