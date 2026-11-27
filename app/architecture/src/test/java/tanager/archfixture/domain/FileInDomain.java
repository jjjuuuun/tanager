package tanager.archfixture.domain;

/** 위반 픽스처: domain 이 파일 I/O 를 쓴다. */
public class FileInDomain {
    boolean exists(String path) {
        return new java.io.File(path).exists();
    }
}
