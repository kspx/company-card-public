package zone.cogni.companycard.service;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.repository.RdfRepository;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OfficeService {
  private static final Logger log = LoggerFactory.getLogger(OfficeService.class);

  private final RdfRepository repository;
  private final WikidataEnrichmentService wikidata;

  public OfficeService(RdfRepository repository, WikidataEnrichmentService wikidata) {
    this.repository = repository;
    this.wikidata = wikidata;
  }

  public void deriveFromWikidata() {
    Map<String, String> targets = repository.read(this::orgsNeedingOffice);
    if (targets.isEmpty()) {
      log.info("No Wikidata-linked organisation needs a derived office");
      return;
    }
    Map<String, WikidataEnrichmentService.Headquarters> resolved = new LinkedHashMap<>();
    targets.forEach((orgUri, wikidataUri) -> {
      WikidataEnrichmentService.Headquarters hq = wikidata.fetchHeadquarters(wikidataUri);
      if (hq != null) resolved.put(orgUri, hq);
    });
    if (resolved.isEmpty()) {
      log.info("Wikidata returned no headquarters location for {} candidate organisation(s)", targets.size());
      return;
    }
    repository.write(model -> resolved.forEach((orgUri, hq) ->
      Offices.set(model, model.createResource(orgUri), hq.city(), hq.countryIso3(), null, null)));
    log.info("Derived {} office(s) from Wikidata headquarters", resolved.size());
  }

  private Map<String, String> orgsNeedingOffice(Model model) {
    Map<String, String> result = new LinkedHashMap<>();
    Property sameAs = model.createProperty(Namespaces.SCHEMA + "sameAs");
    StmtIterator orgs = model.listStatements(null, RDF.type, model.createResource(Namespaces.CC + "Organization"));
    try {
      while (orgs.hasNext()) {
        Resource org = orgs.next().getSubject();
        if (Offices.hasOffice(model, org)) continue;
        StmtIterator links = org.listProperties(sameAs);
        try {
          while (links.hasNext()) {
            RDFNode object = links.next().getObject();
            String value = object.isLiteral() ? object.asLiteral().getString() : object.toString();
            if (value.contains("wikidata.org")) {
              result.put(org.getURI(), value);
              break;
            }
          }
        }
        finally {
          links.close();
        }
      }
    }
    finally {
      orgs.close();
    }
    return result;
  }
}
