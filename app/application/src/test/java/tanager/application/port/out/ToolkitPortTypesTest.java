package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.ToolkitWarning;

/** 도구함 SPI 의 값 타입(#209). */
class ToolkitPortTypesTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();

    @Test
    @DisplayName("ToolkitScan 은 받은 목록을 복사해 밖에서 바꿔도 그대로다")
    void scanCopiesLists() {
        // given
        List<ToolkitWarning> warnings = new ArrayList<>();
        warnings.add(new ToolkitWarning.ProviderFailed(new ProviderId("a")));

        // when
        ToolkitScan scan = new ToolkitScan(List.of(), warnings, Optional.empty());
        warnings.clear();

        // then
        assertThat(scan.warnings()).hasSize(1);
        assertThatThrownBy(() -> scan.items().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("ScanStamp 는 같은 파일 · 시각이면 같다 — 캐시 재사용의 기준")
    void stampEqualityIsByInputs() {
        // given
        Map<Path, Optional<Instant>> inputs = new HashMap<>();
        inputs.put(Path.of("a"), Optional.of(Instant.EPOCH));
        inputs.put(Path.of("b"), Optional.empty());

        // when
        ScanStamp stamp = new ScanStamp(inputs);
        inputs.clear();

        // then
        assertThat(stamp)
                .isEqualTo(new ScanStamp(
                        Map.of(Path.of("a"), Optional.of(Instant.EPOCH), Path.of("b"), Optional.empty())));
        assertThat(stamp).isNotEqualTo(new ScanStamp(Map.of(Path.of("a"), Optional.of(Instant.EPOCH))));
    }

    @Test
    @DisplayName("ToolkitError 는 경로 · 내용 없이 개발자용 설명만 준다")
    void errorsDescribeWithoutDetails() {
        assertThat(new ToolkitError.Unreachable().describe()).isNotBlank();
        assertThat(new ToolkitError.Failed().describe()).isNotBlank();
    }

    @Test
    @DisplayName("ToolkitQuery 는 null 을 받지 않는다")
    void queryRejectsNulls() {
        WorkspacePath path = WorkspacePath.of(WIN, "C:\\work").value().orElseThrow();

        assertThatThrownBy(() -> new ToolkitQuery(path, null, _ -> Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolkitQuery(path, Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("지문을 주지 않는 공급자의 stamp 기본값은 empty 다(캐시하지 않음)")
    void defaultStampIsEmpty() {
        // given
        ToolkitProvider provider = new ToolkitProvider() {
            @Override
            public ProviderId id() {
                return new ProviderId("plain");
            }

            @Override
            public boolean supports(ShellEnvironment env) {
                return true;
            }

            @Override
            public Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query) {
                return Result.err(new ToolkitError.Failed());
            }
        };
        WorkspacePath path = WorkspacePath.of(WIN, "C:\\work").value().orElseThrow();

        // when
        Optional<ScanStamp> stamp = provider.stamp(new ToolkitQuery(path, Optional.empty(), _ -> Optional.empty()));

        // then
        assertThat(stamp).isEmpty();
    }
}
