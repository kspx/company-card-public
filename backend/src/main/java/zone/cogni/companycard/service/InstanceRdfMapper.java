package zone.cogni.companycard.service;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;
import zone.cogni.companycard.config.OntologyConfig;
import zone.cogni.companycard.exception.OntologyException;
import zone.cogni.companycard.model.FormField;
import zone.cogni.companycard.model.UriUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class InstanceRdfMapper {
  private final SchemaService schemaService;
  private final OntologyConfig config;

  public InstanceRdfMapper(SchemaService schemaService, OntologyConfig config) {
    this.schemaService = schemaService;
    this.config = config;
  }

  public void writeInstance(String instanceUri, String classUri, Map<String, Object> values,
                            Model writeModel, Model lookupModel) {
    Resource instance = writeModel.createResource(instanceUri)
                                  .addProperty(RDF.type, writeModel.createResource(classUri));

    Map<String, String> propertyUriMap = schemaService.getPropertyUriMap(classUri);
    Map<String, String> datatypeMap = buildDatatypeMap(classUri);

    values.forEach((key, value) -> {
      if (value == null || "uri".equals(key)) return;

      String propertyUri = propertyUriMap.getOrDefault(key, config.getOntologyNamespace() + key);
      Property property = writeModel.createProperty(propertyUri);
      String datatypeUri = datatypeMap.get(key);
      String inverseUri = schemaService.getInversePropertyUri(propertyUri);
      Property inverseProperty = inverseUri != null ? writeModel.createProperty(inverseUri) : null;

      if (value instanceof List<?> list) {
        list.forEach(item -> addSingleValue(instance, property, inverseProperty, datatypeUri, item, writeModel, lookupModel));
      }
      else {
        addSingleValue(instance, property, inverseProperty, datatypeUri, value, writeModel, lookupModel);
      }
    });
  }

  public List<Statement> collectTriplesForRemoval(Resource instance, Model model, Set<String> managedPropertyUris) {
    List<Statement> toRemove = new ArrayList<>();
    instance.listProperties()
            .filterDrop(stmt -> RDF.type.equals(stmt.getPredicate()))
            .filterKeep(stmt -> managedPropertyUris.contains(stmt.getPredicate().getURI()))
            .forEachRemaining(stmt -> {
              toRemove.add(stmt);
              if (stmt.getObject()
                      .isResource()) {
                collectInverseStatement(instance, stmt, model, toRemove);
              }
            });
    return toRemove;
  }

  public List<Statement> collectInverseTriplesForRemoval(Resource instance, Model model) {
    List<Statement> toRemove = new ArrayList<>();
    instance.listProperties()
            .filterKeep(stmt -> stmt.getObject()
                                    .isResource())
            .forEachRemaining(stmt -> collectInverseStatement(instance, stmt, model, toRemove));
    return toRemove;
  }

  private Map<String, String> buildDatatypeMap(String classUri) {
    return schemaService.getFormSchema(classUri)
                        .fields()
                        .stream()
                        .filter(FormField::isDatatypeProperty)
                        .filter(f -> f.range() != null && !f.range()
                                                            .isEmpty())
                        .collect(Collectors.toMap(
                          f -> UriUtils.extractLocalName(f.property()),
                          FormField::range,
                          (a, b) -> a
                        ));
  }

  private void addSingleValue(Resource instance, Property property, Property inverseProperty,
                              String datatypeUri, Object value, Model writeModel, Model lookupModel) {
    if (value == null) return;
    String stringValue = value.toString();
    if (stringValue.isBlank()) return;

    if (datatypeUri != null) {
      addLiteralValue(instance, property, datatypeUri, stringValue, writeModel);
    }
    else if (UriUtils.isUri(stringValue)) {
      addUriValue(instance, property, inverseProperty, stringValue, writeModel, lookupModel);
    }
    else {
      addLiteralValue(instance, property, null, stringValue, writeModel);
    }
  }

  private void addUriValue(Resource instance, Property property, Property inverseProperty,
                           String targetUri, Model writeModel, Model lookupModel) {
    if (targetUri.startsWith(config.getBaseDataUri())) {
      boolean exists = lookupModel.contains(lookupModel.createResource(targetUri), RDF.type, (RDFNode) null);
      if (!exists) {
        throw OntologyException.validationFailed("Referenced entity does not exist: " + targetUri);
      }
    }

    Resource target = writeModel.createResource(targetUri);
    instance.addProperty(property, target);

    if (inverseProperty != null) {
      assertNotExclusivelyShared(target, instance, inverseProperty, lookupModel);
      target.addProperty(inverseProperty, instance);
    }

    String superPropUri = schemaService.getSubPropertyImplications()
                                       .get(property.getURI());
    if (superPropUri != null) {
      Property superProperty = writeModel.createProperty(superPropUri);
      instance.addProperty(superProperty, target);
      String superInverseUri = schemaService.getInversePropertyUri(superPropUri);
      if (superInverseUri != null) {
        target.addProperty(writeModel.createProperty(superInverseUri), instance);
      }
    }
  }

  private void assertNotExclusivelyShared(Resource target, Resource owner, Property inverseProperty, Model lookupModel) {
    if (!schemaService.getExclusivePropertyUris()
                      .contains(inverseProperty.getURI())) return;

    Property inversePropInLookup = lookupModel.createProperty(inverseProperty.getURI());
    boolean alreadyLinkedToOther = lookupModel
      .listStatements(target, inversePropInLookup, (RDFNode) null)
      .filterDrop(s -> s.getObject()
                        .isResource()
                       && s.getObject()
                           .asResource()
                           .getURI()
                           .equals(owner.getURI()))
      .hasNext();

    if (alreadyLinkedToOther) {
      throw OntologyException.validationFailed(
        "'" + UriUtils.extractLocalName(target.getURI()) + "' is already linked to another entity and cannot be shared.");
    }
  }

  private void addLiteralValue(Resource instance, Property property, String datatypeUri, String value, Model writeModel) {
    if (datatypeUri != null && !datatypeUri.isEmpty()) {
      RDFDatatype dtype = TypeMapper.getInstance()
                                    .getSafeTypeByName(datatypeUri);
      instance.addProperty(property, writeModel.createTypedLiteral(value, dtype));
    }
    else {
      instance.addProperty(property, value);
    }
  }

  private void collectInverseStatement(Resource instance, Statement stmt, Model model, List<Statement> accumulator) {
    String inverseUri = schemaService.getInversePropertyUri(stmt.getPredicate()
                                                                .getURI());
    if (inverseUri == null) return;
    Resource target = stmt.getObject()
                          .asResource();
    Property inverseProp = model.createProperty(inverseUri);
    Statement inverseStmt = model.createStatement(target, inverseProp, instance);
    if (model.contains(inverseStmt)) {
      accumulator.add(inverseStmt);
    }
  }
}
