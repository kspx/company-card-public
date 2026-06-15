package zone.cogni.companycard.repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.exception.OntologyException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

@Repository
public class RdfRepository {
  private static final Logger log = LoggerFactory.getLogger(RdfRepository.class);

  private final OntologyConfig config;

  private Dataset dataset;
  private Model ontologyModel;
  private Model shapesModel;
  private Model vocabulariesModel;

  public RdfRepository(OntologyConfig config) {
    this.config = config;
  }

  @PostConstruct
  void initialize() {
    log.info("Initializing RDF repository...");

    this.dataset = TDB2Factory.connectDataset(config.getDatasetPath());

    this.ontologyModel = loadModelFromClasspath("company-card.ttl");
    this.shapesModel = loadModelFromClasspath("company-card-shapes.ttl");
    this.vocabulariesModel = loadModelsFromClasspath("classpath*:vocabularies/*.ttl");

    log.info("RDF repository initialized successfully");
  }

  @PreDestroy
  void shutdown() {
    log.info("Shutting down RDF repository...");
    if (dataset != null) {
      dataset.close();
    }
  }

  public Model ontology() {
    return ontologyModel;
  }

  public Model shapes() {
    return shapesModel;
  }

  public Model vocabularies() {
    return vocabulariesModel;
  }

  public <T> T read(Function<Model, T> operation) {
    dataset.begin(ReadWrite.READ);
    try {
      return operation.apply(dataset.getDefaultModel());
    }
    catch (RuntimeException e) {
      dataset.abort();
      throw e;
    }
    finally {
      dataset.end();
    }
  }

  public String getVocabularyLabel(String uri) {
    if (uri == null) return null;
    Resource res = vocabulariesModel.createResource(uri);
    Property prefLabel = vocabulariesModel.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel");
    StmtIterator it = vocabulariesModel.listStatements(res, prefLabel, (org.apache.jena.rdf.model.RDFNode) null);
    while (it.hasNext()) {
      Literal lit = it.nextStatement().getObject().asLiteral();
      String lang = lit.getLanguage();
      if ("en".equals(lang) || lang.isEmpty()) {
        return lit.getString();
      }
    }
    return null;
  }

  public void write(Consumer<Model> operation) {
    dataset.begin(ReadWrite.WRITE);
    try {
      operation.accept(dataset.getDefaultModel());
      dataset.commit();
    }
    catch (Exception e) {
      dataset.abort();
      throw e;
    }
    finally {
      dataset.end();
    }
  }

  private Model loadModelFromClasspath(String filename) {
    return Optional.ofNullable(getClass().getClassLoader()
                                         .getResource(filename))
                   .map(URL::toString)
                   .map(RDFDataMgr::loadModel)
                   .orElseThrow(() -> new OntologyException(filename + " not found in classpath"));
  }

  private Model loadModelsFromClasspath(String pattern) {
    Model model = ModelFactory.createDefaultModel();
    try {
      var resources = new PathMatchingResourcePatternResolver().getResources(pattern);
      if (resources.length == 0) {
        throw new OntologyException("no resources matched " + pattern);
      }
      for (var resource : resources) {
        try (InputStream in = resource.getInputStream()) {
          RDFDataMgr.read(model, in, Lang.TURTLE);
        }
      }
    }
    catch (IOException e) {
      throw new OntologyException("failed to load " + pattern, e);
    }
    return model;
  }
}
