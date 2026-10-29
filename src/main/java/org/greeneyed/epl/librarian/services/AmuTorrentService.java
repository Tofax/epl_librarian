package org.greeneyed.epl.librarian.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;

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
      .connectTimeout(Duration.ofSeconds(8))
      .build();
  private final Set<String> submittedMagnets = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    log.info("aMuTorrent: {} llibres catalans absents de Calibre; {} amb magnet enviable i {} sense magnet", missingCatalan, withMagnet,
        withoutMagnet);
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
      // Required by aMuTorrent when selecting the configured download client.
      body.put("clientId", clientId);
      // aMuTorrent REST v1 documents this field as categoryName.
      body.put("categoryName", category);
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/downloads/magnets"))
          .timeout(Duration.ofSeconds(20))
          .header("X-API-Key", apiKey)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        submittedMagnets.remove(magnet);
        log.warn("aMuTorrent ha rebutjat '{}' (HTTP {}): {}", book.getTitulo(), response.statusCode(), response.body());
      } else {
        log.info("Llibre '{}' enviat a aMuTorrent", book.getTitulo());
      }
    } catch (Exception e) {
      submittedMagnets.remove(magnet);
      log.warn("Error enviant '{}' a aMuTorrent: {}", book.getTitulo(), e.getMessage());
    }
  }
}
