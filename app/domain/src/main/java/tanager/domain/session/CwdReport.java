package tanager.domain.session;

import java.util.Objects;

/** 셸이 OSC 로 알린 현재 작업 경로. 어느 환경의 경로인지는 아직 모른다 — {@link CwdReports#toPath} 가 해석한다. */
public sealed interface CwdReport {

    /** OSC 7 {@code file://host/경로}. 퍼센트 디코딩이 끝난 값이고 {@code path} 는 '/' 로 시작한다. */
    record FileUri(String host, String path) implements CwdReport {
        public FileUri {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("OSC 7 경로는 '/' 로 시작해야 한다: " + path);
            }
        }
    }

    /** OSC 9;9 Windows 경로. 따옴표는 벗긴 값이다. */
    record WindowsPath(String path) implements CwdReport {
        public WindowsPath {
            Objects.requireNonNull(path, "path");
        }
    }
}
