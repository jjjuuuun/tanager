package dev.tanager.archfixture.domain;

/** 위반 픽스처: domain 이 java.nio.file 을 쓴다(StandardCharsets 외의 java.nio 는 금지). */
public class NioFilesInDomain {
    boolean exists(String path) {
        return java.nio.file.Files.exists(java.nio.file.Path.of(path));
    }
}
