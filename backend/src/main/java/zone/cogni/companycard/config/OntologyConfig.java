package zone.cogni.companycard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "ontology")
public class OntologyConfig {
    private String baseDataUri = "https://data.cogni.zone/";
    private String ontologyNamespace = "https://ontology.cogni.zone/company-card#";
    private String datasetPath = "data/companycard_tdb";

    private Set<String> hiddenClasses = Set.of(
            "Employment", "ProjectContribution", "TeamMembership",
            "Certification", "PersonalCertification", "OrganizationalCertification"
    );

    public String getBaseDataUri() {
        return baseDataUri;
    }

    public void setBaseDataUri(String baseDataUri) {
        this.baseDataUri = baseDataUri;
    }

    public String getOntologyNamespace() {
        return ontologyNamespace;
    }

    public void setOntologyNamespace(String ontologyNamespace) {
        this.ontologyNamespace = ontologyNamespace;
    }

    public String getDatasetPath() {
        return datasetPath;
    }

    public void setDatasetPath(String datasetPath) {
        this.datasetPath = datasetPath;
    }

    public Set<String> getHiddenClasses() {
        return hiddenClasses;
    }

    public void setHiddenClasses(Set<String> hiddenClasses) {
        this.hiddenClasses = hiddenClasses;
    }
}
