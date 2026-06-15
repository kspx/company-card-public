package zone.cogni.companycard.service;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import zone.cogni.companycard.model.Namespaces;
import zone.cogni.companycard.model.UriUtils;

public final class Offices {
  private Offices() {}

  private static final String OFFICE_CLASS = Namespaces.CC + "Office";
  private static final String OFFICE_OF    = Namespaces.CC + "officeOf";
  private static final String HAS_OFFICE   = Namespaces.CC + "hasOffice";
  private static final String POST_NAME    = Namespaces.LOCN + "postName";
  private static final String ADMIN_UNIT   = Namespaces.LOCN + "adminUnitL1";
  private static final String THOROUGHFARE = Namespaces.LOCN + "thoroughfare";
  private static final String POST_CODE    = Namespaces.LOCN + "postCode";

  public static String set(Model model, Resource org, String city, String countryIso3,
                           String street, String postCode) {
    if (org == null || city == null || city.isBlank() || countryIso3 == null || countryIso3.isBlank()) return null;
    String uri = Namespaces.DATA + "Office/" + UriUtils.sanitizeForUri(UriUtils.extractLocalName(org.getURI()) + "-" + city);
    Resource office = model.createResource(uri);
    if (model.contains(office, RDF.type, model.createResource(OFFICE_CLASS))) return uri;
    office.addProperty(RDF.type, model.createResource(OFFICE_CLASS));
    office.addProperty(model.createProperty(OFFICE_OF), org);
    office.addProperty(model.createProperty(POST_NAME), model.createTypedLiteral(city, XSDDatatype.XSDstring));
    office.addProperty(model.createProperty(ADMIN_UNIT), model.createResource(Namespaces.COUNTRY + countryIso3));
    if (street != null && !street.isBlank())
      office.addProperty(model.createProperty(THOROUGHFARE), model.createTypedLiteral(street, XSDDatatype.XSDstring));
    if (postCode != null && !postCode.isBlank())
      office.addProperty(model.createProperty(POST_CODE), model.createTypedLiteral(postCode, XSDDatatype.XSDstring));
    org.addProperty(model.createProperty(HAS_OFFICE), office);
    return uri;
  }

  public static boolean hasOffice(Model model, Resource org) {
    return model.contains(org, model.createProperty(HAS_OFFICE), (RDFNode) null);
  }
}
