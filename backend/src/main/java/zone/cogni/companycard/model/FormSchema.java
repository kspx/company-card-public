package zone.cogni.companycard.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record FormSchema(
        @JsonProperty("class") String classUri,
        List<FormField> fields
) {
}
