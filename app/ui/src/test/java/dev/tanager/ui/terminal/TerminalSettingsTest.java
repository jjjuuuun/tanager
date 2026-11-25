package dev.tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jediterm.terminal.ui.TerminalActionPresentation;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.theme.JediTermColors;
import dev.tanager.ui.theme.SoftDarkLaf;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 터미널 단축키는 Windows Terminal 관례를 따르고 셸이 쓰는 키를 가로채지 않는다. */
class TerminalSettingsTest {
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    private LookAndFeel previous;
    private TerminalSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new SoftDarkLaf());
        settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current());
    }

    @AfterEach
    void tearDown() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private List<KeyStroke> allBoundKeys() {
        List<TerminalActionPresentation> presentations = List.of(
                settings.getOpenUrlActionPresentation(),
                settings.getCopyActionPresentation(),
                settings.getPasteActionPresentation(),
                settings.getClearBufferActionPresentation(),
                settings.getPageUpActionPresentation(),
                settings.getPageDownActionPresentation(),
                settings.getLineUpActionPresentation(),
                settings.getLineDownActionPresentation(),
                settings.getFindActionPresentation(),
                settings.getSelectAllActionPresentation());
        List<KeyStroke> keys = new ArrayList<>();
        for (TerminalActionPresentation p : presentations) {
            keys.addAll(p.getKeyStrokes());
        }
        return keys;
    }

    @Test
    @DisplayName("Ctrl+C · Ctrl+V · Ctrl+L · Ctrl+F · Ctrl+↑/↓ · Shift+PgUp/PgDn 은 터미널이 가로채지 않고 셸로 간다")
    void shellKeysAreNotIntercepted() {
        assertThat(allBoundKeys())
                .doesNotContain(
                        key(KeyEvent.VK_C, CTRL),
                        key(KeyEvent.VK_V, CTRL),
                        key(KeyEvent.VK_L, CTRL),
                        key(KeyEvent.VK_F, CTRL),
                        key(KeyEvent.VK_UP, CTRL),
                        key(KeyEvent.VK_DOWN, CTRL),
                        key(KeyEvent.VK_PAGE_UP, SHIFT),
                        key(KeyEvent.VK_PAGE_DOWN, SHIFT));
    }

    @Test
    @DisplayName("복사 · 붙여넣기 · 찾기 · 스크롤은 Windows Terminal 기본 단축키다")
    void windowsTerminalShortcuts() {
        assertThat(settings.getCopyActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_C, CTRL | SHIFT), key(KeyEvent.VK_INSERT, CTRL));
        assertThat(settings.getPasteActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_V, CTRL | SHIFT), key(KeyEvent.VK_INSERT, SHIFT));
        assertThat(settings.getFindActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_F, CTRL | SHIFT));
        assertThat(settings.getPageUpActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_PAGE_UP, CTRL | SHIFT));
        assertThat(settings.getPageDownActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_PAGE_DOWN, CTRL | SHIFT));
        assertThat(settings.getLineUpActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_UP, CTRL | SHIFT));
        assertThat(settings.getLineDownActionPresentation().getKeyStrokes())
                .containsExactly(key(KeyEvent.VK_DOWN, CTRL | SHIFT));
        assertThat(settings.getClearBufferActionPresentation().getKeyStrokes()).isEmpty();
        assertThat(settings.getSelectAllActionPresentation().getKeyStrokes()).isEmpty();
    }

    @Test
    @DisplayName("글꼴 14pt · 줄 간격 1.2 · 안티에일리어싱 · type-ahead 끔 · 종 소리 끔")
    void renderingDefaults() {
        assertThat(settings.getTerminalFont().getSize2D()).isEqualTo(14f);
        assertThat(settings.getLineSpacing()).isEqualTo(1.2f);
        assertThat(settings.useAntialiasing()).isTrue();
        assertThat(settings.getTypeAheadSettings().isEnabled()).isFalse();
        assertThat(settings.audibleBell()).isFalse();
    }

    @Test
    @DisplayName("찾기 강조 · 링크 색은 테마 팔레트에서 온다")
    void highlightColorsComeFromTheme() {
        JediTermColors colors = JediTermColors.current();

        assertThat(settings.getFoundPatternColor()).isEqualTo(colors.foundPattern());
        assertThat(settings.getHyperlinkColor()).isEqualTo(colors.hyperlink());
    }
}
