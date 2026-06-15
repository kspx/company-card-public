package zone.cogni.companycard.model;

import org.springframework.util.StringUtils;

public record InstanceReference(
        String uri,
        String label,
        String status
) {
    public static InstanceReference of(String uri, String label) {
        return of(uri, label, null);
    }

    public static InstanceReference of(String uri, String label, String status) {
        String displayLabel = StringUtils.hasText(label) ? label : UriUtils.extractLocalName(uri);
        return new InstanceReference(uri, displayLabel, status);
    }
}
