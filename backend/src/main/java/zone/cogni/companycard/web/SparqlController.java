package zone.cogni.companycard.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
@PreAuthorize("hasRole('ADMIN')")
public class SparqlController {
    private final RdfRepository rdfRepository;

    public SparqlController(RdfRepository rdfRepository) {
        this.rdfRepository = rdfRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> get(@RequestParam String query) {
        return run(query);
    }

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> post(@RequestParam String query) {
        return run(query);
    }

    @PostMapping(consumes = {"application/sparql-query", "text/plain"})
    public ResponseEntity<List<Map<String, Object>>> postBody(@RequestBody String query) {
        return run(query);
    }

    private ResponseEntity<List<Map<String, Object>>> run(String query) {
        List<Map<String, Object>> rows = rdfRepository.read(model ->
            SparqlExecutor.select(model, query, row -> {
                Map<String, Object> map = new LinkedHashMap<>();
                row.varNames().forEachRemaining(var -> {
                    var node = row.get(var);
                    if (node != null) map.put(var, SparqlExecutor.getValue(node));
                });
                return map;
            })
        );
        return ResponseEntity.ok(rows);
    }
}
