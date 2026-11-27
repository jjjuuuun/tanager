package tanager.platform.windows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import tanager.application.port.out.SystemAppearance;

/**
 * Windows 의 외관 설정을 읽는다(#152). '애니메이션 효과'(설정 → 접근성 → 시각 효과)를 끄면 {@code
 * HKCU\Control Panel\Desktop\WindowMetrics} 의 {@code MinAnimate} 가 {@code "0"} 이 된다.
 *
 * <p>{@code SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)} 가 더 정확하지만 이 모듈은 네이티브 호출(JNA)에 의존하지 않는다 —
 * 셸 탐지처럼 외부 명령({@code reg.exe})으로 읽는다. 읽지 못하면 애니메이션을 켠다.
 */
final class WindowsAppearance {
    private static final String KEY = "HKCU\\Control Panel\\Desktop\\WindowMetrics";
    private static final String VALUE = "MinAnimate";
    private static final List<String> QUERY = List.of("reg.exe", "query", KEY, "/v", VALUE);

    private WindowsAppearance() {}

    /** {@code reg.exe query} 로 읽는다. 블로킹(최대 {@code timeout}). 예외를 던지지 않는다. */
    static SystemAppearance read(CommandRunner runner, Duration timeout) {
        boolean animations = switch (runner.run(QUERY, timeout)) {
            case CommandOutcome.Exited(int code, byte[] stdout)
            when code == 0 -> minAnimate(stdout).orElse(SystemAppearance.DEFAULT.animations());
            case CommandOutcome.Exited _, CommandOutcome.NotStarted _, CommandOutcome.TimedOut _ ->
                SystemAppearance.DEFAULT.animations();
        };
        return new SystemAppearance(animations);
    }

    /**
     * {@code reg.exe query} 출력에서 {@code MinAnimate} 값을 읽는다: {@code "0"} 이면 꺼짐, 그 밖의 값이면 켜짐, 값 줄이 없으면 비어 있다.
     * 값 줄({@code "    MinAnimate    REG_SZ    0"})은 ASCII 라 콘솔 코드 페이지와 상관없다.
     */
    static Optional<Boolean> minAnimate(byte[] output) {
        for (String line : new String(output, StandardCharsets.ISO_8859_1).split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length >= 3 && fields[0].equalsIgnoreCase(VALUE)) {
                return Optional.of(!fields[2].equals("0"));
            }
        }
        return Optional.empty();
    }
}
