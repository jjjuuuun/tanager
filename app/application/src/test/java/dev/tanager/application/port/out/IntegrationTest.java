package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrationTest {

    @Test
    @DisplayName("Installed 는 디렉터리 없이 만들 수 없다")
    void installedRequiresDirectory() {
        // when / then
        assertThatThrownBy(() -> new Integration.Installed(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Unavailable 은 이유가 null 이거나 공백이면 거부한다")
    void unavailableRequiresReason() {
        // when / then
        assertThatThrownBy(() -> new Integration.Unavailable(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Integration.Unavailable("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Integration.Unavailable("  \t")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Installed 와 Unavailable 은 값으로 비교된다")
    void valueEquality() {
        // when / then
        assertThat(new Integration.Installed(Path.of("a"))).isEqualTo(new Integration.Installed(Path.of("a")));
        assertThat(new Integration.Unavailable("M7")).isEqualTo(new Integration.Unavailable("M7"));
        assertThat(new Integration.Unavailable("M7")).isNotEqualTo(new Integration.Installed(Path.of("M7")));
    }

    @Property
    @Label("공백이 아닌 글자가 하나라도 있는 이유는 항상 보존된다")
    void nonBlankReasonIsKept(@ForAll @AlphaChars @StringLength(min = 1, max = 40) String reason) {
        // when
        Integration.Unavailable unavailable = new Integration.Unavailable(reason);

        // then
        assertThat(unavailable.reason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("FakePlatformModule 은 integration() 으로 지정한 셸 통합을 돌려준다")
    void fakePlatformExposesIntegration() {
        // given
        FakeShellIntegration custom = FakeShellIntegration.unavailable("테스트");
        FakePlatformModule platform = new FakePlatformModule(List.of()).withIntegration(custom);

        // when
        Integration result = platform.integration().install();

        // then
        assertThat(platform.integration()).isSameAs(custom);
        assertThat(result).isEqualTo(new Integration.Unavailable("테스트"));
        assertThat(custom.installCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("FakePlatformModule 의 기본 integration() 은 Installed 를 돌려준다")
    void fakePlatformDefaultIsInstalled() {
        // when
        Integration result = new FakePlatformModule(List.of()).integration().install();

        // then
        assertThat(result).isInstanceOf(Integration.Installed.class);
    }

    @Test
    @DisplayName("FakePlatformModule 에 withAppData 를 주면 기본 integration() 도 그 아래 shell-integration 을 가리킨다")
    void fakePlatformDefaultIntegrationFollowsAppData() {
        // given
        Path appData = Path.of("custom-appdata", "Tanager");

        // when
        Integration result = new FakePlatformModule(List.of())
                .withAppData(appData)
                .integration()
                .install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(appData.resolve("shell-integration")));
    }

    @Test
    @DisplayName("FakePlatformModule 에 지정한 integration() 은 나중에 withAppData 를 줘도 바뀌지 않는다")
    void fakePlatformCustomIntegrationSurvivesAppDataChange() {
        // given
        FakeShellIntegration custom = FakeShellIntegration.unavailable("테스트");

        // when
        FakePlatformModule platform = new FakePlatformModule(List.of())
                .withIntegration(custom)
                .withAppData(Path.of("custom-appdata", "Tanager"));

        // then
        assertThat(platform.integration()).isSameAs(custom);
    }
}
