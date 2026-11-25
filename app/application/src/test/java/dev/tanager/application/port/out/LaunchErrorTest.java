package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LaunchErrorTest {

    @Test
    @DisplayName("모든 오류는 대상(실행 파일 · 경로 · 상세)을 담은 비어 있지 않은 설명을 준다")
    void describeMentionsTheSubject() {
        assertThat(new LaunchError.ExecutableNotFound("C:\\nope.exe").describe())
                .contains("C:\\nope.exe");
        assertThat(new LaunchError.WorkingDirectoryNotFound("C:\\없는 폴더").describe())
                .contains("C:\\없는 폴더");
        assertThat(new LaunchError.StartFailed("접근 거부").describe()).isNotBlank().contains("접근 거부");
    }
}
