package tanager.platform.windows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.Theme;

/**
 * Windows 의 외관 설정을 읽는다.
 *
 * <ul>
 *   <li>다크/라이트(#184): {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize} 의 {@code
 *       AppsUseLightTheme}. {@code 0x0} 이면 다크, {@code 0x1} 이면 라이트(설정 → 개인 설정 → 색 → 기본 앱 모드).
 *   <li>'애니메이션 효과'(#152, 설정 → 접근성 → 시각 효과): 끄면 {@code HKCU\Control Panel\Desktop\WindowMetrics} 의 {@code
 *       MinAnimate} 가 {@code "0"} 이 된다.
 * </ul>
 *
 * <p>{@code SystemParametersInfo} · {@code RegNotifyChangeKeyValue} 가 더 정확하거나 변경을 바로 알리지만 이 모듈은 네이티브 호출(JNA)에
 * 의존하지 않는다 — 셸 탐지처럼 외부 명령({@code reg.exe})으로 읽고, 변경은 application 이 주기적으로 다시 읽어 안다(ADR-0018). 읽지 못한
 * 테마는 비어 있고 애니메이션은 켠다.
 */
final class WindowsAppearance {
    private static final String THEME_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String THEME_VALUE = "AppsUseLightTheme";
    private static final String ANIMATION_KEY = "HKCU\\Control Panel\\Desktop\\WindowMetrics";
    private static final String ANIMATION_VALUE = "MinAnimate";

    private WindowsAppearance() {}

    /** {@code reg.exe query} 를 값마다 한 번씩(두 번) 실행한다. 블로킹(명령마다 최대 {@code timeout}). 예외를 던지지 않는다. */
    static SystemAppearance read(CommandRunner runner, Duration timeout) {
        Optional<Theme> theme =
                query(runner, THEME_KEY, THEME_VALUE, timeout).flatMap(WindowsAppearance::appsUseLightTheme);
        boolean animations = query(runner, ANIMATION_KEY, ANIMATION_VALUE, timeout)
                .flatMap(WindowsAppearance::minAnimate)
                .orElse(SystemAppearance.DEFAULT.animations());
        return new SystemAppearance(theme, animations);
    }

    // 종료 코드 0 의 표준 출력만 믿는다. 값이 없으면 reg.exe 는 1 로 끝난다
    private static Optional<byte[]> query(CommandRunner runner, String key, String value, Duration timeout) {
        return switch (runner.run(List.of("reg.exe", "query", key, "/v", value), timeout)) {
            case CommandOutcome.Exited(int code, byte[] stdout) when code == 0 -> Optional.of(stdout);
            case CommandOutcome.Exited _, CommandOutcome.NotStarted _, CommandOutcome.TimedOut _ -> Optional.empty();
        };
    }

    /**
     * {@code reg.exe query} 출력에서 {@code AppsUseLightTheme} 값을 읽는다: {@code 0x0} 이면 다크, {@code 0x1} 이면 라이트, 값 줄이 없거나 그
     * 밖의 값이면 비어 있다.
     */
    static Optional<Theme> appsUseLightTheme(byte[] output) {
        return value(output, THEME_VALUE).flatMap(text -> switch (text.toLowerCase(Locale.ROOT)) {
            case "0x0" -> Optional.of(Theme.DARK);
            case "0x1" -> Optional.of(Theme.LIGHT);
            default -> Optional.empty();
        });
    }

    /**
     * {@code reg.exe query} 출력에서 {@code MinAnimate} 값을 읽는다: {@code "0"} 이면 꺼짐, 그 밖의 값이면 켜짐, 값 줄이 없으면 비어 있다.
     */
    static Optional<Boolean> minAnimate(byte[] output) {
        return value(output, ANIMATION_VALUE).map(text -> !text.equals("0"));
    }

    // 값 줄("    이름    REG_SZ    값")의 값. 값 줄은 ASCII 라 콘솔 코드 페이지와 상관없다. 이름은 대소문자를 가리지 않는다
    private static Optional<String> value(byte[] output, String name) {
        for (String line : new String(output, StandardCharsets.ISO_8859_1).split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length >= 3 && fields[0].equalsIgnoreCase(name)) {
                return Optional.of(fields[2]);
            }
        }
        return Optional.empty();
    }
}
