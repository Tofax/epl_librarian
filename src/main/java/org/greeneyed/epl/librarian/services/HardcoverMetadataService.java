package org.greeneyed.epl.librarian.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.greeneyed.epl.librarian.model.Libro;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Resolves EPL books to Hardcover book IDs and persists the result locally. */
@Service
@Slf4j
@RequiredArgsConstructor
public class HardcoverMetadataService {
  private static final String ENDPOINT = "https://api.hardcover.app/v1/graphql";
  private static final String QUERY = "query Search($query:String!, $query_type:String!, $per_page:Int!) { "
      + "search(query:$query, query_type:$query_type, per_page:$per_page) { ids } }";

  private final ObjectMapper objectMapper;
  private final Environment environment;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  private final Map<String, Result> cache = new ConcurrentHashMap<>();
  private final Map<String, Long> notFoundCache = new ConcurrentHashMap<>();
  private final AtomicInteger attemptsThisRun = new AtomicInteger();

  @Value("${hardcover_enrichment:false}")
  private boolean enabled;

  @Value("${hardcover_api_token:}")
  private String apiToken;

  @Value("${hardcover_enrichment_max_per_run:50}")
  private int maximumAttemptsPerRun;

  @Value("${hardcover_enrichment_delay_ms:1200}")
  private long requestDelayMs;

  @Value("${hardcover_not_found_ttl_days:30}")
  private int notFoundTtlDays;

  @Value("${superportable:false}")
  private boolean superPortable;

  private Path cacheFile;
  private Path notFoundCacheFile;
  private long lastRequestAt;
  private volatile long rateLimitedUntil;

  @PostConstruct
  void loadCache() {
    cacheFile = PreferencesService.findPreferencesFile(superPortable, environment)
        .toPath()
        .resolveSibling("hardcover-cache.json");
    notFoundCacheFile = cacheFile.resolveSibling("hardcover-not-found.json");
    read(cacheFile, new TypeReference<Map<String, Result>>() {
    }, cache);
    read(notFoundCacheFile, new TypeReference<Map<String, Long>>() {
    }, notFoundCache);
    log.info("Cache de Hardcover carregada: {} entrades", cache.size());
  }

  public boolean isEnabled() {
    return enabled && apiToken != null && !apiToken.isBlank();
  }

  public void beginRun() {
    attemptsThisRun.set(0);
    rateLimitedUntil = 0;
  }

  public void enrich(Libro libro) {
    if (!isEnabled() || libro == null || libro.getHardcoverIdNormalizado() != null || libro.getTitulo() == null
        || rateLimitedUntil > System.currentTimeMillis()) {
      return;
    }
    String key = cacheKey(libro);
    Long missingAt = notFoundCache.get(key);
    if (missingAt != null && missingAt > System.currentTimeMillis() - Duration.ofDays(notFoundTtlDays)
        .toMillis()) {
      return;
    }
    if (attemptsThisRun.incrementAndGet() > maximumAttemptsPerRun) {
      return;
    }
    Result result = cache.get(key);
    if (result == null) {
      result = lookup(libro);
      if (result != null) {
        cache.put(key, result);
        notFoundCache.remove(key);
        save(cacheFile, cache);
      } else if (rateLimitedUntil <= System.currentTimeMillis()) {
        notFoundCache.put(key, System.currentTimeMillis());
        save(notFoundCacheFile, notFoundCache);
      }
    }
    if (result != null) {
      libro.setHardcoverId(result.hardcoverId());
    }
  }

  public boolean searchNow(Libro libro) {
    if (!isEnabled() || libro == null || libro.getTitulo() == null || rateLimitedUntil > System.currentTimeMillis()) {
      return false;
    }
    Result result = cache.get(cacheKey(libro));
    if (result == null) {
      result = lookup(libro);
      if (result != null) {
        cache.put(cacheKey(libro), result);
        save(cacheFile, cache);
      }
    }
    if (result == null) {
      return false;
    }
    libro.setHardcoverId(result.hardcoverId());
    return true;
  }

  public boolean setManualHardcoverId(Libro libro,
      String value) {
    String normalized = Libro.hardcoverIdNormalizado(value);
    if (libro == null || normalized == null) {
      return false;
    }
    libro.setHardcoverId(normalized);
    cache.put(cacheKey(libro), new Result(normalized, "manual"));
    save(cacheFile, cache);
    return true;
  }

  private String cacheKey(Libro libro) {
    return Libro.tituloComparable(libro.getTitulo()) + "|" + Libro.flattenToAscii(libro.getAutor()) + "|" + libro.getAnyoPublicacion();
  }

  private Result lookup(Libro libro) {
    try {
      long wait = requestDelayMs - (System.currentTimeMillis() - lastRequestAt);
      if (wait > 0)
        Thread.sleep(wait);
      String searchText = (libro.getTitulo() + " " + (libro.getAutor() == null ? "" : libro.getAutor())).trim();
      String body = objectMapper
          .writeValueAsString(Map.of("query", QUERY, "variables", Map.of("query", searchText, "query_type", "book", "per_page", 5)));
      HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
          .timeout(Duration.ofSeconds(20))
          .header("Authorization", "Bearer " + apiToken.trim())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
      lastRequestAt = System.currentTimeMillis();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429) {
        rateLimitedUntil = System.currentTimeMillis() + Duration.ofHours(6)
            .toMillis();
        log.warn("Hardcover ha limitat les peticions (429). Pausant consultes durant 6 hores.");
        return null;
      }
      if (response.statusCode() / 100 != 2) {
        log.warn("Hardcover ha respost HTTP {}", response.statusCode());
        return null;
      }
      JsonNode ids = objectMapper.readTree(response.body())
          .path("data")
          .path("search")
          .path("ids");
      if (!ids.isArray() || ids.isEmpty())
        return null;
      String id = ids.get(0)
          .asText();
      return id.isBlank() ? null : new Result(id, "hardcover");
    } catch (InterruptedException e) {
      Thread.currentThread()
          .interrupt();
      return null;
    } catch (Exception e) {
      log.warn("No s'ha pogut consultar Hardcover per '{}': {}", libro.getTitulo(), e.getMessage());
      return null;
    }
  }

  private void read(Path file,
      TypeReference<? extends Map> type,
      Map target) {
    try {
      if (Files.isRegularFile(file))
        target.putAll(objectMapper.readValue(Files.readString(file), type));
    } catch (Exception e) {
      log.warn("No s'ha pogut carregar la cache de Hardcover {}: {}", file, e.getMessage());
    }
  }

  private synchronized void save(Path file,
      Map<?, ?> values) {
    try {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsString(new HashMap<>(values)));
      Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("No s'ha pogut guardar la cache de Hardcover {}: {}", file, e.getMessage());
    }
  }

  private record Result(String hardcoverId, String provider) {
  }
}
