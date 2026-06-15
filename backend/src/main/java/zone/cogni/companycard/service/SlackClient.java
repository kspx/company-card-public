package zone.cogni.companycard.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.util.StringUtils;

@Component
public class SlackClient {
  private static final Logger log = LoggerFactory.getLogger(SlackClient.class);
  private static final String BASE_URL = "https://slack.com/api";

  private final HttpClient http = HttpClient.newHttpClient();

  private final ObjectMapper mapper = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  @Value("${app.slack.token:}")
  private String token;

  public record SlackUser(
    String id,
    String handle,
    String realName,
    String firstName,
    String lastName,
    String email,
    String phone,
    String title,
    String imageUrl,
    long created
  ) {}

  public record SlackTeamInfo(
    String id,
    String name,
    String publicUrl,
    String slackUrl,
    String iconUrl
  ) {}

  private interface SlackResponse {
    boolean ok();

    String error();
  }

  private interface Paginated extends SlackResponse {
    ResponseMetadata responseMetadata();
  }

  private record Profile(
    String realName, String firstName, String lastName,
    String email, String phone, String title,
    @JsonProperty("image_192") String image192
  ) {
    static final Profile EMPTY = new Profile(null, null, null, null, null, null, null);
  }

  private record RawMember(
    String id,
    String name,
    String realName,
    boolean isBot, boolean isAppUser,
    boolean deleted,
    boolean isRestricted,
    boolean isUltraRestricted,
    long created,
    Profile profile
  ) {}

  private record ResponseMetadata(String nextCursor) {}

  private record RawIcon(@JsonProperty("image_132") String image132) {}

  private record RawTeam(String id, String name, String url, @JsonProperty("public_url") String publicUrl, RawIcon icon) {}

  private record ConvListChannel(String id, String name) {}

  private record ConvHistoryMessage(String subtype, String user, String ts) {}

  private record UsersListResponse(boolean ok, List<RawMember> members, ResponseMetadata responseMetadata, String error) implements Paginated {}

  private record TeamInfoResponse(boolean ok, RawTeam team, String error) implements SlackResponse {}

  private record ConvListResponse(boolean ok, List<ConvListChannel> channels, ResponseMetadata responseMetadata, String error) implements Paginated {}

  private record ConvHistoryResponse(boolean ok, List<ConvHistoryMessage> messages, ResponseMetadata responseMetadata, String error) implements Paginated {}

  public List<SlackUser> getWorkspaceUsers() {
    requireToken();
    List<SlackUser> result = new ArrayList<>();
    eachPage(BASE_URL + "/users.list?limit=200", UsersListResponse.class, "users.list",
      resp -> resp.members()
                  .stream()
                  .filter(SlackClient::isHuman)
                  .map(SlackClient::toSlackUser)
                  .forEach(result::add));
    log.debug("Fetched {} workspace users from Slack", result.size());
    return result;
  }

  public SlackTeamInfo getWorkspaceInfo() {
    requireToken();
    RawTeam t = ok(get(BASE_URL + "/team.info", TeamInfoResponse.class), "team.info").team();
    return new SlackTeamInfo(t.id(), t.name(), t.publicUrl(), t.url(), t.icon() != null ? t.icon()
                                                                                           .image132() : null);
  }

  public Map<String, String> getChannelJoinDates(String channelName) {
    requireToken();
    String channelId = findChannelId(channelName);
    if (channelId == null) {
      log.warn("  Channel '{}' not found — skipping join dates", channelName);
      return Collections.emptyMap();
    }

    Map<String, String> joinDates = new HashMap<>();
    eachPage(BASE_URL + "/conversations.history?channel=" + channelId + "&limit=200",
      ConvHistoryResponse.class, "conversations.history", resp -> {
        if (resp.messages() == null) return;
        for (ConvHistoryMessage msg : resp.messages()) {
          if (!"channel_join".equals(msg.subtype()) || msg.user() == null || msg.ts() == null) continue;
          try {
            String date = Instant.ofEpochSecond((long) Double.parseDouble(msg.ts()))
                                 .atZone(ZoneOffset.UTC)
                                 .toLocalDate()
                                 .toString();
            joinDates.merge(msg.user(), date, (ex, in) -> in.compareTo(ex) < 0 ? in : ex);
          }
          catch (NumberFormatException ignored) {}
        }
      });
    log.info("  Found join dates for {} users in #{}", joinDates.size(), channelName);
    return joinDates;
  }

  private static boolean isHuman(RawMember m) {
    return !m.isBot() && !m.isAppUser() && !m.deleted()
           && !m.isRestricted() && !m.isUltraRestricted()
           && !"USLACKBOT".equals(m.id());
  }

  private static SlackUser toSlackUser(RawMember m) {
    Profile p = m.profile() != null ? m.profile() : Profile.EMPTY;
    return new SlackUser(m.id(), m.name(), firstNonBlank(p.realName(), m.realName()),
      p.firstName(), p.lastName(), p.email(), p.phone(), p.title(), p.image192(), m.created());
  }

  private <T extends Paginated> void eachPage(String baseUrl, Class<T> cls, String method, Consumer<T> action) {
    String cursor = null;
    do {
      T resp = ok(get(baseUrl + (cursor != null ? "&cursor=" + cursor : ""), cls), method);
      action.accept(resp);
      cursor = nextCursor(resp);
    } while (cursor != null);
  }

  private String findChannelId(String channelName) {
    String cursor = null;
    do {
      ConvListResponse resp = ok(get(BASE_URL + "/conversations.list?types=public_channel&limit=200"
                                     + (cursor != null ? "&cursor=" + cursor : ""), ConvListResponse.class), "conversations.list");
      String id = Optional.ofNullable(resp.channels())
                          .orElse(List.of())
                          .stream()
                          .filter(ch -> channelName.equals(ch.name()))
                          .map(ConvListChannel::id)
                          .findFirst()
                          .orElse(null);
      if (id != null) return id;
      cursor = nextCursor(resp);
    } while (cursor != null);
    return null;
  }

  private static String nextCursor(Paginated resp) {
    return Optional.ofNullable(resp.responseMetadata())
                   .map(ResponseMetadata::nextCursor)
                   .filter(StringUtils::hasText)
                   .orElse(null);
  }

  private static <T extends SlackResponse> T ok(T r, String method) {
    if (!r.ok()) throw new IllegalStateException(method + " failed: " + r.error());
    return r;
  }

  private <T> T get(String url, Class<T> type) {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(url))
                                     .header("Authorization", "Bearer " + token)
                                     .GET()
                                     .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
      return mapper.readValue(response.body(), type);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling " + url, e);
    }
    catch (java.io.IOException e) {
      throw new IllegalStateException("Failed calling " + url + ": " + e.getMessage(), e);
    }
  }

  private void requireToken() {
    if (!StringUtils.hasText(token)) throw new IllegalStateException("app.slack.token is not configured — add it to application.yml");
  }

  private static String firstNonBlank(String... values) {
    return Arrays.stream(values)
                 .filter(StringUtils::hasText)
                 .findFirst()
                 .orElse(null);
  }
}
