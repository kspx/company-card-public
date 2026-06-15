package zone.cogni.companycard.model;

import org.apache.jena.rdf.model.ResourceFactory;

import java.text.Normalizer;
import java.util.UUID;
import java.util.regex.Pattern;

public final class UriUtils {
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    public static String extractLocalName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        return ResourceFactory.createResource(uri).getLocalName();
    }

    public static String sanitizeForUri(String text) {
        if (text == null || text.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = Normalizer.normalize(text.trim().toLowerCase(), Normalizer.Form.NFD);
        return NON_ALPHANUMERIC.matcher(normalized)
                .replaceAll("-")
                .replaceAll("^-|-$", "");
    }

    public static boolean isUri(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    public static String humanize(String slug) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        String spaced = slug.replaceAll("[-_]+", " ")
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .trim();
        StringBuilder result = new StringBuilder();
        for (String token : spaced.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                result.append(token.substring(1));
            }
        }
        return result.toString();
    }
}
