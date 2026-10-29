package org.greeneyed.epl.librarian.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  @Value("${amutorrent_category:EPL}")
  private String category;

  public void submitMissingCatalanBooks(Iterable<Libro> books) {
    if (!enabled || apiKey.isBlank() || instanceId.isBlank()) {
      return;
    }
    for (Libro book : books) {
      if (book.getInCalibre() || !isCatalan(book.getIdioma())) {
        continue;
      }
      for (String magnet : book.getListaMagnetIds()) {
        if (magnet.startsWith("magnet:") && submittedMagnets.add(magnet)) {
          submit(magnet, book);
          break;
        }
      }
    }
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
      body.put("categoryName", category);
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/downloads/magnets"))
          .timeout(Duration.ofSeconds(20))
          .header("X-API-Key", apiKey)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        submittedMagnets.remove(magnet);
        log.warn("aMuTorrent ha rebutjat '{}' (HTTP {})", book.getTitulo(), response.statusCode());
      } else {
        log.info("Llibre '{}' enviat a aMuTorrent", book.getTitulo());
      }
    } catch (Exception e) {
      submittedMagnets.remove(magnet);
      log.warn("Error enviant '{}' a aMuTorrent: {}", book.getTitulo(), e.getMessage());
    }
  }
}
