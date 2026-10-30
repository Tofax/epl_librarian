package org.greeneyed.epl.librarian.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.greeneyed.epl.librarian.model.Libro;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Optional bridge to aMuTorrent. Disabled unless explicitly configured. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AmuTorrentService {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
      // aMuTorrent serves plain HTTP/1.1. Java's default HTTP/2 preference
      // sends an h2c preface over cleartext, which Express reports as the
      // misleading 405 "Invalid HTTP method" response.
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(8))
      .build();
  private final Set<String> submittedMagnets = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final Pattern MAGNET_HASH = Pattern.compile("(?:[?&])xt=urn:btih:([^&]+)", Pattern.CASE_INSENSITIVE);

  @Value("${amutorrent_enabled:false}")
  private boolean enabled;
  @Value("${amutorrent_url:http://localhost:4000}")
  private String baseUrl;
  @Value("${amutorrent_api_key:}")
  private String apiKey;
  @Value("${amutorrent_instance_id:}")
  private String instanceId;
  @Value("${amutorrent_client_id:qbittorrent}")
  private String clientId;
  @Value("${amutorrent_category:EPL}")
  private String category;

  public void submitMissingCatalanBooks(Iterable<Libro> books) {
    if (!enabled || apiKey.isBlank() || instanceId.isBlank()) {
      log.warn("aMuTorrent no està actiu o falta configuració (enabled={}, apiKey={}, instanceId={})", enabled, !apiKey.isBlank(),
          !instanceId.isBlank());
      return;
    }
    int missingCatalan = 0;
    int withMagnet = 0;
    int withoutMagnet = 0;
    int alreadyQueued = 0;
    Set<String> queuedHashes = loadQueuedHashes();
    for (Libro book : books) {
      if (book.getInCalibre() || !isCatalan(book.getIdioma())) {
        continue;
      }
      missingCatalan++;
      boolean foundMagnet = false;
      for (String magnetId : book.getListaMagnetIds()) {
        String magnet = toMagnet(magnetId, book);
        if (magnet != null) {
          foundMagnet = true;
          String hash = magnetHash(magnet);
          if (hash != null && queuedHashes.contains(hash)) {
            alreadyQueued++;
            break;
          }
          if (submittedMagnets.add(magnet)) {
            withMagnet++;
            submit(magnet, book);
          }
          break;
        }
      }
      if (!foundMagnet) {
        withoutMagnet++;
      }
    }
    log.info("aMuTorrent: {} llibres catalans absents de Calibre; {} amb magnet enviable, {} ja en cua i {} sense magnet", missingCatalan, withMagnet,
        alreadyQueued, withoutMagnet);
  }

  private Set<String> loadQueuedHashes() {
    Set<String> result = new HashSet<>();
    if (!enabled || apiKey.isBlank() || instanceId.isBlank()) {
      return result;
    }
    try {
      URI endpoint = URI.create(normalizedBaseUrl() + "/api/v1/data/snapshot");
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(10))
          .header("X-API-Key", apiKey)
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn("No s'han pogut consultar les descàrregues existents d'aMuTorrent (HTTP {})", response.statusCode());
        return result;
      }
      var root = objectMapper.readTree(response.body());
      var items = root.path("data")
          .path("items");
      if (items.isArray()) {
        for (var item : items) {
          String hash = item.path("hash")
              .asText(null);
          if (hash != null && !hash.isBlank()) {
            result.add(hash.toLowerCase());
          }
        }
      }
    } catch (Exception e) {
      log.warn("No s'han pogut consultar les descàrregues existents d'aMuTorrent: {}", e.getMessage());
    }
    return result;
  }

  private String magnetHash(String magnet) {
    Matcher matcher = MAGNET_HASH.matcher(magnet);
    return matcher.find() ? matcher.group(1)
        .toLowerCase() : null;
  }

  private String toMagnet(String magnetId,
      Libro book) {
    if (magnetId == null || magnetId.isBlank()) {
      return null;
    }
    String value = magnetId.trim();
    if (value.startsWith("magnet:")) {
      return value;
    }
    String encodedTitle = URLEncoder.encode(book.getTitulo(), StandardCharsets.UTF_8)
        .replace("+", "%20");
    return "magnet:?xt=urn:btih:" + value + "&dn=EPL_" + book.getId() + "_" + encodedTitle + "&tr=http://tracker.tfile.me/announce"
        + "&tr=udp://tracker.opentrackr.org:1337/announce" + "&tr=udp://tracker.openbittorrent.com:80" + "&tr=udp://tracker.publicbt.com:80"
        + "&tr=udp://open.demonii.com:1337/announce";
  }

  private boolean isCatalan(String language) {
    if (language == null) {
      return false;
    }
    String normalized = Libro.flattenToAscii(language.trim())
        .toLowerCase();
    return "catalan".equals(normalized) || "catala".equals(normalized);
  }

  private void submit(String magnet,
      Libro book) {
    try {
      HashMap<String, Object> body = new HashMap<>();
      body.put("links", java.util.List.of(magnet));
      body.put("instanceId", instanceId);
      // aMuTorrent REST v1 documents this field as categoryName.
      body.put("categoryName", category);
      URI endpoint = URI.create(normalizedBaseUrl() + "/api/v1/downloads/magnets");
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(20))
          .header("X-API-Key", apiKey)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        submittedMagnets.remove(magnet);
        log.warn("aMuTorrent ha rebutjat '{}' (POST {}) (HTTP {}, Allow={}): {}", book.getTitulo(), endpoint, response.statusCode(),
            response.headers()
                .firstValue("Allow")
                .orElse("-"),
            response.body());
      } else {
        log.info("Llibre '{}' enviat a aMuTorrent", book.getTitulo());
      }
    } catch (Exception e) {
      submittedMagnets.remove(magnet);
      log.warn("Error enviant '{}' a aMuTorrent: {}", book.getTitulo(), e.getMessage());
    }
  }

  private String normalizedBaseUrl() {
    // Docker Compose commonly provides these settings as lower-case
    // environment variables. Read them directly as a fallback because
    // Spring's @Value placeholder lookup does not consistently expose
    // lower-case underscore names across deployment modes.
    String environmentUrl = System.getenv("amutorrent_url");
    if (environmentUrl == null || environmentUrl.isBlank()) {
      environmentUrl = System.getenv("AMUTorrent_URL");
    }
    String configuredUrl = environmentUrl == null || environmentUrl.isBlank() ? baseUrl : environmentUrl;
    return configuredUrl == null ? "" : configuredUrl.replaceFirst("/+\\z", "");
  }
}
