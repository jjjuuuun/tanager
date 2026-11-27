package tanager.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 화면 글자 번들 고르기와 형식(#136). 테스트 JVM 은 한국어로 고정돼 있다(app/ui/build.gradle.kts). */
class MessagesTest {

    @Test
    @DisplayName("한국어 언어면 한국어, 영어 · 다른 언어면 영어 번들이다")
    void languageChoosesBundle() {
        assertThat(Messages.of(Locale.KOREA).text(Msg.PANE_STARTING)).isEqualTo("셸을 시작하는 중…");
        assertThat(Messages.of(Locale.US).text(Msg.PANE_STARTING)).isEqualTo("Starting shell…");
        assertThat(Messages.of(Locale.JAPAN).text(Msg.PANE_STARTING)).isEqualTo("Starting shell…");
    }

    @Test
    @DisplayName("영어를 고르면 JVM 기본 언어(한국어) 번들로 넘어가지 않는다")
    void englishDoesNotFallBackToDefaultLocale() {
        assertThat(Locale.getDefault(Locale.Category.DISPLAY).getLanguage()).isEqualTo("ko");
        assertThat(Messages.of(Locale.ENGLISH).text(Msg.MENU_COPY_PATH)).isEqualTo("Copy path");
    }

    @Test
    @DisplayName("기본 번들은 JVM 의 표시 언어로 고른다")
    void defaultFollowsDisplayLocale() {
        assertThat(Msg.PANE_STARTING.text()).isEqualTo("셸을 시작하는 중…");
    }

    @Test
    @DisplayName("숫자 인자에 자리 구분 쉼표를 붙이지 않는다")
    void numbersAreNotGrouped() {
        assertThat(Messages.of(Locale.US).text(Msg.PANE_EXIT_CODE, -1073741510)).isEqualTo("Exit code -1073741510");
        assertThat(Messages.of(Locale.KOREA).text(Msg.PANE_EXIT_CODE, 1234)).isEqualTo("종료 코드 1234");
    }

    @Test
    @DisplayName("작은따옴표가 든 문장도 인자가 제자리에 들어간다")
    void apostrophesSurviveFormatting() {
        assertThat(Messages.of(Locale.KOREA).text(Msg.FORGET_CONFIRM, "api"))
                .isEqualTo("'api' 의 기록을 지울까요? 고정 · 별칭도 함께 사라진다.");
        assertThat(Messages.of(Locale.US).text(Msg.FORGET_CONFIRM, "api")).startsWith("Remove 'api' ");
    }
}
