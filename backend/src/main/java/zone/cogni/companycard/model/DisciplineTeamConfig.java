package zone.cogni.companycard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DisciplineTeamConfig(String managementTeamId,
                                   List<Member> managers,
                                   List<Member> disciplines,
                                   List<Designer> designers,
                                   List<TeamDef> teams) {
  public DisciplineTeamConfig {
    managers = managers == null ? List.of() : managers;
    disciplines = disciplines == null ? List.of() : disciplines;
    designers = designers == null ? List.of() : designers;
    teams = teams == null ? List.of() : teams;
  }

  static String personUri(String name, String slackId) {
    String key = (name != null && !name.isBlank()) ? name : slackId;
    return Namespaces.DATA + "Person/" + UriUtils.sanitizeForUri(key);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Member(String person, String name, String discipline) {
    public String personUri() {
      return DisciplineTeamConfig.personUri(name, person);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Designer(String person, String name, List<String> clients) {
    public Designer {
      clients = clients == null ? List.of() : clients;
    }

    public String personUri() {
      return DisciplineTeamConfig.personUri(name, person);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TeamDef(String id, String name, String teamType) {}
}
