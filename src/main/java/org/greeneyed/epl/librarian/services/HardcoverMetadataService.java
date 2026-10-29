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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
  private static final String HYDRATE_QUERY = "query Hydrate($ids:[Int!]!) { books(where:{id:{_in:$ids}}) "
      + "{ id title slug contributions { author { name } } } }";

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
        .resolveSibling("hardcover-cache-v2.json");
    notFoundCacheFile = cacheFile.resolveSibling("hardcover-not-found-v2.json");
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
      libro.setHardcoverSlug(result.slug());
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
    libro.setHardcoverSlug(result.slug());
    return true;
  }

  public boolean setManualHardcoverId(Libro libro,
      String value) {
    String normalized = Libro.hardcoverIdNormalizado(value);
    if (libro == null || normalized == null) {
      return false;
    }
    libro.setHardcoverId(normalized);
    cache.put(cacheKey(libro), new Result(normalized, null, "manual"));
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
      JsonNode search = post(QUERY, Map.of("query", searchText, "query_type", "book", "per_page", 5));
      JsonNode ids = search.path("data")
          .path("search")
          .path("ids");
      if (!ids.isArray() || ids.isEmpty())
        return null;
      Set<Integer> candidateIds = new HashSet<>();
      ids.forEach(id -> {
        if (id.canConvertToInt())
          candidateIds.add(id.asInt());
      });
      if (candidateIds.isEmpty())
        return null;
      JsonNode books = post(HYDRATE_QUERY, Map.of("ids", candidateIds)).path("data")
          .path("books");
      Result best = bestMatch(books, libro);
      if (best == null)
        log.info("Hardcover no ha trobat una coincidència fiable per '{}'", libro.getTitulo());
      return best;
    } catch (InterruptedException e) {
      Thread.currentThread()
          .interrupt();
      return null;
    } catch (Exception e) {
      log.warn("No s'ha pogut consultar Hardcover per '{}': {}", libro.getTitulo(), e.getMessage());
      return null;
    }
  }

  private JsonNode post(String query,
      Map<String, ?> variables) throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("query", query, "variables", variables));
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
      throw new IOException("Hardcover ha limitat les peticions (429)");
    }
    if (response.statusCode() / 100 != 2)
      throw new IOException("Hardcover HTTP " + response.statusCode());
    JsonNode root = objectMapper.readTree(response.body());
    if (root.has("errors"))
      throw new IOException("Hardcover ha retornat errors GraphQL");
    return root;
  }

  private Result bestMatch(JsonNode books,
      Libro target) {
    if (!books.isArray())
      return null;
    Set<String> targetTitle = words(target.getTitulo());
    Set<String> targetAuthors = words(target.getAutor());
    Result best = null;
    double bestScore = 0;
    for (JsonNode book : books) {
      Set<String> candidateTitle = words(book.path("title")
          .asText());
      double titleScore = overlap(targetTitle, candidateTitle);
      if (titleScore < 0.65)
        continue;
      Set<String> candidateAuthors = new HashSet<>();
      book.path("contributions")
          .forEach(contribution -> candidateAuthors.addAll(words(contribution.path("author")
              .path("name")
              .asText())));
      double authorScore = targetAuthors.isEmpty() ? 1.0 : overlap(targetAuthors, candidateAuthors);
      if (!targetAuthors.isEmpty() && authorScore < 0.25)
        continue;
      double score = titleScore * 0.7 + authorScore * 0.3;
      if (score > bestScore && book.path("id")
          .canConvertToInt()) {
        bestScore = score;
        best = new Result(book.path("id")
            .asText(),
            book.path("slug")
                .asText(null),
            "hardcover");
      }
    }
    return best;
  }

  private Set<String> words(String value) {
    Set<String> result = new HashSet<>();
    if (value == null)
      return result;
    for (String word : Libro.flattenToAscii(value)
        .toLowerCase()
        .split("[^a-z0-9]+")) {
      if (word.length() > 2)
        result.add(word);
    }
    return result;
  }

  private double overlap(Set<String> left,
      Set<String> right) {
    if (left.isEmpty())
      return 0;
    Set<String> intersection = new HashSet<>(left);
    intersection.retainAll(right);
    return (double) intersection.size() / left.size();
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

  private record Result(String hardcoverId, String slug, String provider) {
  }
}
