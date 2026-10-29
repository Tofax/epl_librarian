package org.greeneyed.epl.librarian.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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

  @jakarta.annotation.PostConstruct
  void init() {
    linksFile = PreferencesService.findPreferencesFile(false, environment)
        .toPath()
        .resolveSibling("calibre-links.json");
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
}
