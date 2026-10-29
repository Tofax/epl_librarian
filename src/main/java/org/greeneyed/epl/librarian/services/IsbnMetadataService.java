package org.greeneyed.epl.librarian.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.greeneyed.epl.librarian.model.Libro;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enriches catalogue records with ISBNs without trusting a single provider
 * blindly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IsbnMetadataService {
  private final ObjectMapper objectMapper;
  private final Environment environment;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8))
      .build();
  private final Map<String, Result> cache = new ConcurrentHashMap<>();
  private final Map<String, Long> notFoundCache = new ConcurrentHashMap<>();
  private final AtomicInteger attemptsThisRun = new AtomicInteger();

  @Value("${isbn_enrichment:false}")
  private boolean enabled;

  @Value("${isbn_min_confidence:0.80}")
  private double minimumConfidence;

  @Value("${isbn_provider:auto}")
  private String provider;

  @Value("${google_books_api_key:}")
  private String googleBooksApiKey;

  @Value("${isbndb_api_key:}")
  private String isbnDbApiKey;

  @Value("${isbn_enrichment_max_per_run:50}")
  private int maximumAttemptsPerRun;

  @Value("${isbn_enrichment_delay_ms:1200}")
  private long requestDelayMs;

  @Value("${isbn_not_found_ttl_days:30}")
  private int notFoundTtlDays;

  @Value("${superportable:false}")
  private boolean superPortable;

  private Path cacheFile;
  private Path notFoundCacheFile;
  private volatile long rateLimitedUntil;
  private long lastRequestAt;

  @PostConstruct
  void loadCache() {
    cacheFile = PreferencesService.findPreferencesFile(superPortable, environment)
        .toPath()
        .resolveSibling("isbn-cache.json");
    notFoundCacheFile = cacheFile.resolveSibling("isbn-not-found.json");
    try {
      if (Files.isRegularFile(cacheFile)) {
        cache.putAll(objectMapper.readValue(Files.readString(cacheFile), new TypeReference<Map<String, Result>>() {
        }));
        log.info("Cache d'ISBN carregada: {} entrades", cache.size());
      }
    } catch (Exception e) {
      log.warn("No s'ha pogut carregar la cache d'ISBN {}: {}", cacheFile, e.getMessage());
    }
    try {
      if (Files.isRegularFile(notFoundCacheFile)) {
        notFoundCache.putAll(objectMapper.readValue(Files.readString(notFoundCacheFile), new TypeReference<Map<String, Long>>() {
        }));
        log.info("Cache de llibres sense ISBN carregada: {} entrades", notFoundCache.size());
      }
    } catch (Exception e) {
      log.warn("No s'ha pogut carregar la cache de llibres sense ISBN {}: {}", notFoundCacheFile, e.getMessage());
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void beginRun() {
    attemptsThisRun.set(0);
    rateLimitedUntil = 0;
  }

  public void enrich(Libro libro) {
    if (!enabled || libro == null || libro.getIsbnNormalizado() != null || libro.getTitulo() == null) {
      return;
    }
    if (!isCatalan(libro.getIdioma()) || rateLimitedUntil > System.currentTimeMillis()) {
      return;
    }
    String key = Libro.tituloComparable(libro.getTitulo()) + "|" + Libro.flattenToAscii(libro.getAutor()) + "|" + libro.getAnyoPublicacion();
    Long notFoundAt = notFoundCache.get(key);
    if (notFoundAt != null && notFoundAt > System.currentTimeMillis() - Duration.ofDays(notFoundTtlDays)
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
        saveCache();
        notFoundCache.remove(key);
      } else if (rateLimitedUntil <= System.currentTimeMillis()) {
        notFoundCache.put(key, System.currentTimeMillis());
        saveNotFoundCache();
      }
    }
    if (result != null && result.confidence() >= minimumConfidence) {
      libro.setIsbn(result.isbn());
      log.debug("ISBN {} asignado a '{}' con confianza {} mediante {}", result.isbn(), libro.getTitulo(), result.confidence(), result.provider());
    }
  }

  private boolean isCatalan(String language) {
    String normalized = Libro.flattenToAscii(language);
    return "ca".equals(normalized) || "cat".equals(normalized) || "catala".equals(normalized) || "catalan".equals(normalized);
  }

  private synchronized void saveNotFoundCache() {
    try {
      Files.createDirectories(notFoundCacheFile.getParent());
      Path temporary = notFoundCacheFile.resolveSibling(notFoundCacheFile.getFileName() + ".tmp");
      Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsString(new HashMap<>(notFoundCache)));
      Files.move(temporary, notFoundCacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("No s'ha pogut guardar la cache de llibres sense ISBN {}: {}", notFoundCacheFile, e.getMessage());
    }
  }

  private synchronized void saveCache() {
    try {
      Files.createDirectories(cacheFile.getParent());
      Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
      Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsString(new HashMap<>(cache)));
      Files.move(temporary, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("No s'ha pogut guardar la cache d'ISBN {}: {}", cacheFile, e.getMessage());
    }
  }

  private Result lookup(Libro libro) {
    try {
      Result best = null;
      if ("isbndb".equalsIgnoreCase(provider) || "auto".equalsIgnoreCase(provider)) {
        best = lookupIsbnDb(libro);
      }
      if ("googlebooks".equalsIgnoreCase(provider) || "google".equalsIgnoreCase(provider) || "auto".equalsIgnoreCase(provider)) {
        Result google = lookupGoogleBooks(libro);
        if (google != null && (best == null || google.confidence() > best.confidence())) {
          best = google;
        }
      }
      if (best != null && best.confidence() >= minimumConfidence) {
        return best;
      }
      Result openLibrary = lookupOpenLibrary(libro);
      return openLibrary != null && (best == null || openLibrary.confidence() > best.confidence()) ? openLibrary : best;
    } catch (RateLimitException e) {
      rateLimitedUntil = System.currentTimeMillis() + Duration.ofHours(6)
          .toMillis();
      log.warn("Proveïdor d'ISBN ha limitat les peticions (429). Pausant consultes durant 6 hores.");
      return null;
    } catch (Exception e) {
      log.debug("No s'ha pogut obtenir ISBN per '{}': {}", libro.getTitulo(), e.getMessage());
      return null;
    }
  }

  private Result lookupIsbnDb(Libro libro) throws Exception {
    if (isbnDbApiKey.isBlank()) {
      return null;
    }
    String query = "title:" + quote(libro.getTitulo()) + " author:" + quote(libro.getAutor());
    JsonNode root = get("https://api2.isbndb.com/books/" + encode(query), "x-api-key", isbnDbApiKey);
    Result best = null;
    for (JsonNode item : root.path("books")) {
      double score = score(libro, item.path("title")
          .asText(), item.path("authors"), item.path("language"));
      String isbn = validIsbnNode(item.path("isbn13"));
      if (isbn == null) {
        isbn = validIsbnNode(item.path("isbn"));
      }
      if (isbn != null && score >= minimumConfidence && (best == null || score > best.confidence())) {
        best = new Result(isbn, score, "isbndb");
      }
    }
    return best;
  }

  private Result lookupOpenLibrary(Libro libro) throws Exception {
    String query = "title:" + quote(libro.getTitulo()) + " author:" + quote(libro.getAutor());
    String url = "https://openlibrary.org/search.json?q=" + encode(query)
        + "&fields=title,author_name,isbn,language,publisher,first_publish_year&limit=5";
    JsonNode root = get(url);
    Result best = null;
    for (JsonNode item : root.path("docs")) {
      double score = score(libro, item.path("title")
          .asText(), item.path("author_name"), item.path("language"));
      if (score >= minimumConfidence && item.path("isbn")
          .isArray()
          && item.path("isbn")
              .size() > 0) {
        String isbn = firstValidIsbn(item.path("isbn"));
        if (isbn != null && (best == null || score > best.confidence())) {
          best = new Result(isbn, score, "openlibrary");
        }
      }
    }
    return best;
  }

  private Result lookupGoogleBooks(Libro libro) throws Exception {
    String apiKey = googleBooksApiKey.isBlank() ? "" : "&key=" + encode(googleBooksApiKey);
    String query = "intitle:" + quote(libro.getTitulo()) + " inauthor:" + quote(libro.getAutor());
    JsonNode root = get("https://www.googleapis.com/books/v1/volumes?q=" + encode(query) + "&maxResults=5&printType=books" + apiKey);
    Result best = null;
    for (JsonNode item : root.path("items")) {
      JsonNode info = item.path("volumeInfo");
      double score = score(libro, info.path("title")
          .asText(), info.path("authors"), info.path("language"));
      for (JsonNode identifier : info.path("industryIdentifiers")) {
        String isbn = Libro.isbnNormalizado(identifier.path("identifier")
            .asText());
        if (isbn != null && (isbn.length() == 10 || isbn.length() == 13) && score >= minimumConfidence
            && (best == null || score > best.confidence())) {
          best = new Result(isbn, score, "googlebooks");
        }
      }
    }
    return best;
  }

  private JsonNode get(String url) throws Exception {
    return get(url, null, null);
  }

  private JsonNode get(String url,
      String headerName,
      String headerValue) throws Exception {
    synchronized (this) {
      long wait = requestDelayMs - (System.currentTimeMillis() - lastRequestAt);
      if (wait > 0) {
        Thread.sleep(wait);
      }
      lastRequestAt = System.currentTimeMillis();
    }
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", "EPL-Librarian/ISBN-enrichment")
        .GET();
    if (headerName != null && headerValue != null) {
      requestBuilder.header(headerName, headerValue);
    }
    HttpRequest request = requestBuilder.build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 429) {
      throw new RateLimitException();
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("HTTP " + response.statusCode());
    }
    return objectMapper.readTree(response.body());
  }

  private double score(Libro libro,
      String title,
      JsonNode authors,
      JsonNode language) {
    double score = Libro.tituloComparable(libro.getTitulo())
        .equals(Libro.tituloComparable(title)) ? 0.60 : 0.0;
    String expectedAuthor = Libro.flattenToAscii(libro.getAutor());
    for (JsonNode author : authors) {
      if (Libro.flattenToAscii(author.asText())
          .equals(expectedAuthor)) {
        score += 0.25;
      }
    }
    String expectedLanguage = Libro.flattenToAscii(libro.getIdioma());
    if (expectedLanguage == null || !language.isArray() || language.size() == 0 || language.toString()
        .contains(expectedLanguage.substring(0, Math.min(3, expectedLanguage.length())))) {
      score += 0.15;
    }
    return score;
  }

  private String firstValidIsbn(JsonNode values) {
    for (JsonNode value : values) {
      String isbn = Libro.isbnNormalizado(value.asText());
      if (isbn != null && (isbn.length() == 10 || isbn.length() == 13)) {
        return isbn;
      }
    }
    return null;
  }

  private String validIsbnNode(JsonNode value) {
    return value.isArray() ? firstValidIsbn(value) : validIsbn(value.asText());
  }

  private String validIsbn(String value) {
    String isbn = Libro.isbnNormalizado(value);
    return isbn != null && (isbn.length() == 10 || isbn.length() == 13) ? isbn : null;
  }

  private static String quote(String value) {
    return "\"" + (value == null ? "" : value.replace("\"", "")) + "\"";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Result(String isbn, double confidence, String provider) {
  }

  private static final class RateLimitException extends Exception {
    private static final long serialVersionUID = 1L;
  }
}
