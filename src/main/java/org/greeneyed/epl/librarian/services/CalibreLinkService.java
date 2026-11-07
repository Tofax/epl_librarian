package org.greeneyed.epl.librarian.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists the local EPL-to-Calibre associations without changing metadata.db.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CalibreLinkService {
  private final ObjectMapper objectMapper;
  private final Environment environment;
  private Path linksFile;
  private Path manualOverridesFile;

  @jakarta.annotation.PostConstruct
  void init() {
    linksFile = PreferencesService.findPreferencesFile(false, environment)
        .toPath()
        .resolveSibling("calibre-links.json");
    manualOverridesFile = linksFile.resolveSibling("calibre-manual-overrides.json");
  }

  public synchronized void save(Map<Integer, Integer> links) {
    try {
      Files.createDirectories(linksFile.getParent());
      Path tmp = linksFile.resolveSibling("calibre-links.json.tmp");
      Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsString(new HashMap<>(links)));
      Files.move(tmp, linksFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("No s'ha pogut guardar el mapa de vinculacions amb Calibre: {}", e.getMessage());
    }
  }

  public synchronized Map<Integer, Integer> load() {
    try {
      if (Files.isRegularFile(linksFile)) {
        return objectMapper.readValue(Files.readString(linksFile), new TypeReference<Map<Integer, Integer>>() {
        });
      }
    } catch (Exception e) {
      log.warn("No s'ha pogut carregar el mapa de vinculacions amb Calibre: {}", e.getMessage());
    }
    return new HashMap<>();
  }

  public synchronized Map<Integer, Boolean> loadManualOverrides() {
    try {
      if (Files.isRegularFile(manualOverridesFile)) {
        Map<String, Boolean> stored = objectMapper.readValue(Files.readString(manualOverridesFile),
            new TypeReference<Map<String, Boolean>>() {
            });
        Map<Integer, Boolean> result = new HashMap<>();
        stored.forEach((id, value) -> {
          try {
            result.put(Integer.valueOf(id), value);
          } catch (NumberFormatException ignored) {
            // Ignore malformed entries while preserving the valid overrides.
          }
        });
        return result;
      }
    } catch (Exception e) {
      log.warn("No s'ha pogut carregar el mapa de confirmacions manuals de Calibre: {}", e.getMessage());
    }
    return new HashMap<>();
  }

  public synchronized void setManualOverride(Integer eplId, boolean inCalibre) {
    Map<Integer, Boolean> overrides = loadManualOverrides();
    overrides.put(eplId, inCalibre);
    saveManualOverrides(overrides);
  }

  private synchronized void saveManualOverrides(Map<Integer, Boolean> overrides) {
    try {
      Files.createDirectories(manualOverridesFile.getParent());
      Path tmp = manualOverridesFile.resolveSibling("calibre-manual-overrides.json.tmp");
      Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(overrides));
      Files.move(tmp, manualOverridesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("No s'ha pogut desar el mapa de confirmacions manuals de Calibre: {}", e.getMessage());
    }
  }
}
