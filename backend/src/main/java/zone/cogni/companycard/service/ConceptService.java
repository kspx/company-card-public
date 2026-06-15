package zone.cogni.companycard.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.Concept;
import zone.cogni.companycard.repository.RdfRepository;
import zone.cogni.companycard.repository.SparqlExecutor;
import zone.cogni.companycard.repository.SparqlQueries;

import java.util.List;

import static zone.cogni.companycard.repository.SparqlExecutor.*;

@Service
public class ConceptService {
    private final RdfRepository repository;

    public ConceptService(RdfRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "concepts", key = "#propertyUri")
    public List<Concept> getConceptsForProperty(String propertyUri) {
        String query = SparqlQueries.findConceptSchemeForProperty(propertyUri);
        List<String> schemes = SparqlExecutor.select(repository.shapes(), query,
                sol -> getUri(sol, "scheme"));

        if (schemes.isEmpty()) {
            return List.of();
        }

        return getConceptsFromScheme(schemes.get(0));
    }

    public List<Concept> getConceptsFromScheme(String schemeUri) {
        String query = SparqlQueries.findConceptsInScheme(schemeUri);

        return SparqlExecutor.select(repository.vocabularies(), query, sol ->
                Concept.of(
                        getUri(sol, "concept"),
                        getString(sol, "label"),
                        getString(sol, "notation")
                )
        );
    }
}
