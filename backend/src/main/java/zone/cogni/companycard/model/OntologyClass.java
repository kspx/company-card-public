package zone.cogni.companycard.model;

public record OntologyClass(
        String uri,
        String label,
        String comment
) {
    public String localName() {
        return UriUtils.extractLocalName(uri);
    }
}
