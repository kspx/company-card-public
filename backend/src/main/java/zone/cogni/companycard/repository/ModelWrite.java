package zone.cogni.companycard.repository;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.springframework.util.StringUtils;
import zone.cogni.companycard.model.Namespaces;

import java.time.Instant;
import java.util.Collection;

public final class ModelWrite {
  private ModelWrite() {
  }

  public static void clear(Model model, Resource subject, String propUri) {
    model.removeAll(subject, model.createProperty(propUri), null);
  }

  public static void setInt(Model model, Resource subject, String propUri, int value) {
    clear(model, subject, propUri);
    model.add(subject, model.createProperty(propUri), model.createTypedLiteral(value));
  }

  public static void setBoolean(Model model, Resource subject, String propUri, boolean value) {
    clear(model, subject, propUri);
    model.add(subject, model.createProperty(propUri), model.createTypedLiteral(value));
  }

  public static void setDate(Model model, Resource subject, String propUri, String date) {
    clear(model, subject, propUri);
    if (date != null) {
      model.add(subject, model.createProperty(propUri), model.createTypedLiteral(date, XSDDatatype.XSDdate));
    }
  }

  public static void setLiterals(Model model, Resource subject, String propUri, Collection<String> values) {
    clear(model, subject, propUri);
    for (String v : values) model.add(subject, model.createProperty(propUri), v);
  }

  public static void setResource(Model model, Resource subject, String propUri, String uri) {
    clear(model, subject, propUri);
    if (uri != null) model.add(subject, model.createProperty(propUri), model.createResource(uri));
  }

  public static void addIfMissing(Model model, Resource subject, String propUri, String value) {
    Property property = model.createProperty(propUri);
    if (StringUtils.hasText(value) && !model.contains(subject, property)) model.add(subject, property, value);
  }

  public static void addIfMissingUriLiteral(Model model, Resource subject, String propUri, String value) {
    Property property = model.createProperty(propUri);
    if (StringUtils.hasText(value) && !model.contains(subject, property)) {
      model.add(subject, property, model.createTypedLiteral(value, XSDDatatype.XSDanyURI));
    }
  }

  public static void stampNow(Model model, Resource subject) {
    clear(model, subject, Namespaces.DCT + "modified");
    model.add(subject, model.createProperty(Namespaces.DCT + "modified"),
              model.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime));
  }
}
