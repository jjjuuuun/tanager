package dev.tanager.application.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.shared.Result;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformSelectionTest {
    private static final OperatingSystem WINDOWS = new OperatingSystem("Windows 11", "10.0", "amd64");
    private static final OperatingSystem LINUX = new OperatingSystem("Linux", "6.6", "amd64");

    /** 이름이 주어진 접두로 시작하는 OS 를 지원하는 가짜 플랫폼. */
    private record FakePlatform(String id, String osPrefix) implements PlatformModule {
        @Override
        public boolean supports(OperatingSystem os) {
            return os.name().startsWith(osPrefix);
        }

        @Override
        public List<ShellProfile> shells() {
            return List.of();
        }

        @Override
        public LaunchCommand launchCommand(ShellProfile profile, WorkspacePath cwd) {
            throw new UnsupportedOperationException("선택 테스트에서는 쓰지 않는다");
        }
    }

    private static final PlatformModule WIN = new FakePlatform("windows", "Windows");
    private static final PlatformModule MAC = new FakePlatform("macos", "Mac");

    @Test
    @DisplayName("현재 OS 를 지원하는 후보가 하나면 그것을 고른다")
    void selectsTheOnlyMatchingPlatform() {
        // when
        Result<PlatformModule, PlatformSelectionError> result = PlatformSelection.select(List.of(MAC, WIN), WINDOWS);

        // then
        assertThat(result.value()).contains(WIN);
    }

    @Test
    @DisplayName("맞는 후보가 없으면 NoMatch — 어떤 OS 였는지와 후보 id 를 담는다")
    void noMatchWhenNothingSupportsTheOs() {
        // when
        Result<PlatformModule, PlatformSelectionError> result = PlatformSelection.select(List.of(MAC, WIN), LINUX);

        // then
        assertThat(result.error()).contains(new PlatformSelectionError.NoMatch(LINUX, List.of("macos", "windows")));
    }

    @Test
    @DisplayName("후보가 아예 없어도 NoMatch")
    void noMatchWhenThereAreNoCandidates() {
        Result<PlatformModule, PlatformSelectionError> result = PlatformSelection.select(List.of(), WINDOWS);

        assertThat(result.error()).contains(new PlatformSelectionError.NoMatch(WINDOWS, List.of()));
    }

    @Test
    @DisplayName("맞는 후보가 둘 이상이면 Ambiguous — 겹치는 id 를 담는다")
    void ambiguousWhenSeveralSupportTheOs() {
        // given
        PlatformModule otherWin = new FakePlatform("windows-alt", "Win");

        // when
        Result<PlatformModule, PlatformSelectionError> result =
                PlatformSelection.select(List.of(WIN, MAC, otherWin), WINDOWS);

        // then
        assertThat(result.error())
                .contains(new PlatformSelectionError.Ambiguous(WINDOWS, List.of("windows", "windows-alt")));
    }

    @Test
    @DisplayName("오류는 사람이 읽을 수 있는 한 줄 설명을 준다")
    void errorsDescribeThemselves() {
        assertThat(new PlatformSelectionError.NoMatch(LINUX, List.of("windows")).describe())
                .contains("Linux")
                .contains("windows");
        assertThat(new PlatformSelectionError.Ambiguous(WINDOWS, List.of("a", "b")).describe())
                .contains("Windows 11")
                .contains("a, b");
    }
}
