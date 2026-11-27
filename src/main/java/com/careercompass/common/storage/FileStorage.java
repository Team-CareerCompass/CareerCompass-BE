package com.careercompass.common.storage;

/**
 * 파일 저장소 경계. 운영은 S3 비공개 버킷(SSE-KMS) — S3 구현은 AWS 배포(#7)와 함께 붙인다. 로컬·테스트는 {@link LocalFileStorage}.
 */
public interface FileStorage {

  /** 저장 후 참조 URI(예: s3://bucket/key, local://key)를 돌려준다. */
  String store(String key, byte[] content);

  void delete(String key);
}
