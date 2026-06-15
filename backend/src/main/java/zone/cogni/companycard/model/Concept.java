package zone.cogni.companycard.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Concept(
        String uri,
        String label,
        String notation
) {
    public static Concept of(String uri, String label) {
        return new Concept(uri, label, null);
    }

    public static Concept of(String uri, String label, String notation) {
        return new Concept(uri, label, notation);
    }
}
