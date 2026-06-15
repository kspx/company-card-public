package zone.cogni.companycard.service;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AdmsIdentifiers {
  private AdmsIdentifiers() {}

  private static final String IDENTIFIER_CLASS = Namespaces.ADMS + "Identifier";
  private static final String IDENTIFIER       = Namespaces.ADMS + "identifier";
  private static final String NOTATION         = Namespaces.SKOS + "notation";
  private static final String TYPE             = Namespaces.DCT + "type";
  private static final String PAGE             = Namespaces.FOAF + "page";

  public static void set(Model model, Resource subject, String schemeUri, String value) {
    set(model, subject, schemeUri, value, null);
  }

  public static void set(Model model, Resource subject, String schemeUri, String value, String pageUrl) {
    if (value == null || value.isBlank()) return;
    remove(model, subject, schemeUri);
    Resource node = model.createResource(Namespaces.DATA + "Identifier/" + local(schemeUri).toLowerCase() + "-" + UriUtils.sanitizeForUri(value));
    node.removeProperties();
    node.addProperty(RDF.type, model.createResource(IDENTIFIER_CLASS));
    node.addProperty(model.createProperty(NOTATION), model.createTypedLiteral(value, XSDDatatype.XSDstring));
    node.addProperty(model.createProperty(TYPE), model.createResource(schemeUri));
    if (pageUrl != null && !pageUrl.isBlank()) node.addProperty(model.createProperty(PAGE), model.createResource(pageUrl));
    subject.addProperty(model.createProperty(IDENTIFIER), node);
  }

  public static Optional<String> notation(Resource subject, String schemeUri) {
    Property type = subject.getModel().createProperty(TYPE);
    Property notation = subject.getModel().createProperty(NOTATION);
    StmtIterator it = subject.listProperties(subject.getModel().createProperty(IDENTIFIER));
    try {
      while (it.hasNext()) {
        RDFNode object = it.next().getObject();
        if (!object.isResource()) continue;
        Resource node = object.asResource();
        Statement scheme = node.getProperty(type);
        if (scheme != null && scheme.getObject().isResource() && schemeUri.equals(scheme.getResource().getURI())) {
          Statement value = node.getProperty(notation);
          if (value != null) return Optional.of(value.getString());
        }
      }
    }
    finally {
      it.close();
    }
    return Optional.empty();
  }

  private static void remove(Model model, Resource subject, String schemeUri) {
    Property identifier = model.createProperty(IDENTIFIER);
    Property type = model.createProperty(TYPE);
    List<Statement> links = new ArrayList<>();
    List<Resource> nodes = new ArrayList<>();
    StmtIterator it = subject.listProperties(identifier);
    try {
      while (it.hasNext()) {
        Statement link = it.next();
        if (!link.getObject().isResource()) continue;
        Resource node = link.getObject().asResource();
        Statement scheme = node.getProperty(type);
        if (scheme != null && scheme.getObject().isResource() && schemeUri.equals(scheme.getResource().getURI())) {
          links.add(link);
          nodes.add(node);
        }
      }
    }
    finally {
      it.close();
    }
    links.forEach(model::remove);
    nodes.forEach(Resource::removeProperties);
  }

  private static String local(String uri) {
    return uri.substring(Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/')) + 1);
  }
}
