package zone.cogni.companycard.model;

public final class Namespaces {
  private Namespaces() {}

  public static final String DATA   = "https://data.cogni.zone/";
  public static final String CC     = "https://ontology.cogni.zone/company-card#";
  public static final String CCV    = "https://ontology.cogni.zone/company-card-vocabularies#";
  public static final String SCHEMA = "https://schema.org/";
  public static final String DOAP   = "http://usefulinc.com/ns/doap#";
  public static final String ROV    = "http://www.w3.org/ns/regorg#";
  public static final String FOAF   = "http://xmlns.com/foaf/0.1/";
  public static final String DCT    = "http://purl.org/dc/terms/";
  public static final String ADMS   = "http://www.w3.org/ns/adms#";
  public static final String SKOS   = "http://www.w3.org/2004/02/skos/core#";
  public static final String LOCN   = "http://www.w3.org/ns/locn#";
  public static final String COUNTRY = "http://publications.europa.eu/resource/authority/country/";

  public static final String CCV_SLACK   = CCV + "SLACK";
  public static final String CCV_GITHUB  = CCV + "GITHUB";
  public static final String CCV_MANUAL  = CCV + "MANUAL";
  public static final String CCV_DERIVED = CCV + "DERIVED";

  public static final String CCV_ENGAGEMENT_CLIENT    = CCV + "CLIENT";
  public static final String CCV_ENGAGEMENT_ACTIVE    = CCV + "ENG_ACTIVE";
  public static final String CCV_ENGAGEMENT_COMPLETED = CCV + "ENGAGEMENT_COMPLETED";

  public static final String CCV_PROJECT_ONGOING   = CCV + "PROJECT_ONGOING";
  public static final String CCV_PROJECT_COMPLETED = CCV + "PROJECT_COMPLETED";
  public static final String CCV_PROJECT_PAUSED    = CCV + "PROJECT_PAUSED";

  public static final String CCV_AVAILABLE      = CCV + "AVAILABLE";
  public static final String CCV_ON_PROJECT     = CCV + "ON_PROJECT";
  public static final String CCV_ON_LEAVE       = CCV + "ON_LEAVE";
  public static final String CCV_PARTIALLY_AVAILABLE = CCV + "PARTIALLY_AVAILABLE";
  public static final String CCV_NOT_AVAILABLE  = CCV + "NOT_AVAILABLE";
}
