package com.careercompass.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 로컬 파일 시스템 저장소 — S3 구현(#7)이 붙기 전까지의 기본 구현. */
@Component
public class LocalFileStorage implements FileStorage {

  private final Path root;

  public LocalFileStorage(@Value("${app.storage.local-dir:storage}") String rootDir) {
    this.root = Path.of(rootDir).toAbsolutePath().normalize();
  }

  @Override
  public String store(String key, byte[] content) {
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("파일 저장 실패: " + key, e);
    }
    return "local://" + key;
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException e) {
      throw new UncheckedIOException("파일 삭제 실패: " + key, e);
    }
  }

  private Path resolve(String key) {
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("저장소 밖 경로는 허용하지 않습니다: " + key);
    }
    return target;
  }
}
