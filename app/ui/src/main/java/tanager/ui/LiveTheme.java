package tanager.ui;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tanager.application.port.in.ResolvedAppearance;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftTheme;
import tanager.ui.theme.Themed;

/**
 * 정한 테마(설정 + OS, #184)가 바뀌면 화면을 다시 만들지 않고 새 테마로 바꾼다(#185, FR-UI-01 · FR-UI-05). 순서: 스냅샷으로 덮기 →
 * LaF 바꾸기 → 컴포넌트 UI · 토큰 색 다시 칠하기 → 모든 터미널 팔레트 바꾸기 → 화면 밖 부품 정리({@code afterSwitch}) → 페이드로 걷기.
 * 움직임이 꺼져 있으면 덮지 않고 바로 바꾼다(FR-UI-04). 셸은 다시 띄우지 않는다. EDT 에서만 쓴다.
 */
final class LiveTheme {
    private static final System.Logger LOG = System.getLogger(LiveTheme.class.getName());

    /** 바꾸는 동안 화면을 옛 모습으로 덮었다가 걷는다. 실제는 {@link SnapshotFade}. */
    interface Fade {
        /** 지금 화면을 찍어 덮는다(LaF 를 바꾸기 전에 부른다). */
        void cover();

        /** 덮은 것을 서서히 걷는다. */
        void reveal();
    }

    private final TerminalSettings terminal;
    private final Fade fade;
    private final Supplier<List<Component>> roots;
    private final Runnable afterSwitch;
    private SoftTheme current;

    /**
     * @param installed 지금 깔린 테마
     * @param roots 다시 칠할 최상위 컴포넌트들(실제는 열린 창 전부)
     * @param afterSwitch 다시 칠한 뒤 부른다 — 지금 창에 놓이지 않은 부품(선택 화면 · 종료 띠 등)을 정리한다
     */
    LiveTheme(
            SoftTheme installed,
            TerminalSettings terminal,
            Fade fade,
            Supplier<List<Component>> roots,
            Runnable afterSwitch) {
        this.current = Objects.requireNonNull(installed, "installed");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.fade = Objects.requireNonNull(fade, "fade");
        this.roots = Objects.requireNonNull(roots, "roots");
        this.afterSwitch = Objects.requireNonNull(afterSwitch, "afterSwitch");
    }

    /** 지금 깔린 테마. */
    SoftTheme current() {
        return current;
    }

    /** {@code appearance} 의 테마로 바꾼다. 이미 그 테마면 아무것도 하지 않는다. 바꿨으면 true. */
    boolean apply(ResolvedAppearance appearance) {
        SoftTheme next = SoftTheme.of(appearance.theme());
        if (next == current) {
            return false;
        }
        boolean animate = appearance.animations();
        if (animate) {
            fade.cover();
        }
        // FlatLaf.setup 은 실패하면 예외 대신 false 를 준다 — 옛 테마로 남는다
        if (!FlatLaf.setup(next.lookAndFeel())) {
            LOG.log(System.Logger.Level.WARNING, "테마를 바꾸지 못했다: {0}", next);
            if (animate) {
                fade.reveal();
            }
            return false;
        }
        current = next;
        // FlatLaf.updateUI() 와 같다(열린 창마다 updateComponentTreeUI). updateUI 가 덮은 토큰 색은 그 뒤에 다시 칠한다
        for (Component root : roots.get()) {
            SwingUtilities.updateComponentTreeUI(root);
            Themed.recolor(root);
        }
        terminal.colors(JediTermColors.of(next));
        afterSwitch.run();
        if (animate) {
            fade.reveal();
        }
        return true;
    }
}
