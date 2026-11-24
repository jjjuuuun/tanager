package dev.tanager.application.platform;

import java.util.Objects;

/**
 * 실행 중인 OS 정보. 값은 조립 루트(tanager-app)만 시스템에서 읽어 넘긴다 — OS 분기를 한곳에 모으기 위해.
 *
 * @param name 예: "Windows 11", "Mac OS X", "Linux"
 * @param version 예: "10.0"
 * @param arch 예: "amd64", "aarch64"
 */
public record OperatingSystem(String name, String version, String arch) {
    public OperatingSystem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(arch, "arch");
    }

    /** 사람이 읽는 한 줄. */
    public String describe() {
        return name + " " + version + " (" + arch + ")";
    }
}
