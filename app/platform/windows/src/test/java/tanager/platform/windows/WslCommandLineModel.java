package tanager.platform.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기동 argv 가 실제 wsl.exe 에 닿기까지의 두 단계를 흉내 내는 테스트용 모형(#107).
 *
 * <ul>
 *   <li>{@link #joinCmdArgs} — pty4j 0.14.0 {@code WinPtyProcess.joinCmdArgs} 를 소스 그대로 옮겼다(ConPTY 도 같은 함수를 쓴다).
 *       공백 · 탭이 있고 {@code "} 로 시작하지 않는 원소만 {@code "…"} 로 감싸고, 끝 {@code \} 는 하나만 겹친다. 원소 안의 {@code "} 는
 *       이스케이프하지 않는다.
 *   <li>{@link #parseWsl} — wsl.exe 2.5.10 의 인자 파싱을 실측한 규칙(2026-11-27 리뷰). 따옴표 토큰은 {@code "} 로 시작해 다음
 *       {@code "} 에서 끝나고 {@code \"} · {@code ""} 이스케이프가 없다. 비따옴표 토큰 안의 {@code "} · {@code \} 는 글자 그대로다.
 *       {@code --} 를 만나거나 값 옵션이 아닌 토큰을 만나면(옵션 값 뒤에 남은 토큰은 {@code --} 없이도 명령이 된다) 그 뒤 원시 명령줄이
 *       기본 셸의 명령이 된다. 구분은 공백 · 탭만 한다(유니코드 공백으로는 갈리지 않는다).
 * </ul>
 *
 * <p>실측으로 확인하지 않은 모양(닫는 {@code "} 바로 뒤에 글자가 붙음, 닫히지 않은 {@code "})은 추측하지 않고 {@link
 * OutsideModel} 로 실패한다.
 */
final class WslCommandLineModel {
    // 값을 하나 받는 wsl.exe 옵션. Tanager 가 쓰는 것과 사용자 지정에 쓰일 만한 것만 둔다 — 모르는 옵션은 명령 시작으로 본다
    private static final Set<String> VALUE_OPTIONS = Set.of("-d", "--distribution", "--cd", "-u", "--user");
    private static final String SEPARATOR = "--";

    private WslCommandLineModel() {}

    /** wsl.exe 가 읽은 결과. {@code arguments} 는 프로그램 이름부터 명령 앞까지의 인자, {@code command} 는 셸에 넘어가는 원시 명령줄. */
    record WslInvocation(List<String> arguments, Optional<String> command) {
        WslInvocation {
            arguments = List.copyOf(arguments);
        }
    }

    /** 실측하지 않은 모양을 만났다. */
    static final class OutsideModel extends RuntimeException {
        OutsideModel(String message) {
            super(message);
        }
    }

    static String joinCmdArgs(List<String> commands) {
        StringBuilder cmd = new StringBuilder();
        boolean flag = false;
        for (String s : commands) {
            if (flag) {
                cmd.append(' ');
            } else {
                flag = true;
            }
            if (s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0) {
                if (s.charAt(0) != '"') {
                    cmd.append('"').append(s);
                    if (s.endsWith("\\")) {
                        cmd.append("\\");
                    }
                    cmd.append('"');
                } else {
                    cmd.append(s);
                }
            } else {
                cmd.append(s);
            }
        }
        return cmd.toString();
    }

    static WslInvocation parseWsl(String commandLine) {
        List<String> arguments = new ArrayList<>();
        boolean expectingValue = false;
        int pos = 0;
        while (true) {
            pos = skipBlanks(commandLine, pos);
            if (pos == commandLine.length()) {
                return new WslInvocation(arguments, Optional.empty());
            }
            int start = pos;
            boolean quoted = commandLine.charAt(pos) == '"';
            String token;
            if (quoted) {
                int close = commandLine.indexOf('"', pos + 1);
                if (close < 0) {
                    throw new OutsideModel("닫히지 않은 따옴표: " + commandLine.substring(start));
                }
                token = commandLine.substring(pos + 1, close);
                pos = close + 1;
                if (pos < commandLine.length() && !isBlank(commandLine.charAt(pos))) {
                    throw new OutsideModel("닫는 따옴표 뒤에 글자가 붙었다: " + commandLine.substring(start));
                }
            } else {
                int end = pos;
                while (end < commandLine.length() && !isBlank(commandLine.charAt(end))) {
                    end++;
                }
                token = commandLine.substring(pos, end);
                pos = end;
            }
            if (arguments.isEmpty() || expectingValue) {
                arguments.add(token);
                expectingValue = false;
            } else if (!quoted && token.equals(SEPARATOR)) {
                return new WslInvocation(arguments, Optional.of(commandLine.substring(skipBlanks(commandLine, pos))));
            } else if (!quoted && VALUE_OPTIONS.contains(token)) {
                arguments.add(token);
                expectingValue = true;
            } else {
                return new WslInvocation(arguments, Optional.of(commandLine.substring(start)));
            }
        }
    }

    /**
     * {@code argv} 를 pty4j 가 이은 명령줄을 wsl.exe 가 읽었을 때, {@code --} 앞 원소가 그대로 인자가 되고 {@code --} 뒤 원소가 그대로
     * 명령이 되는가.
     */
    static boolean roundTrips(List<String> argv) {
        int separator = argv.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("argv 에 -- 가 없다: " + argv);
        }
        WslInvocation expected = new WslInvocation(
                argv.subList(0, separator), Optional.of(joinCmdArgs(argv.subList(separator + 1, argv.size()))));
        try {
            return parseWsl(joinCmdArgs(argv)).equals(expected);
        } catch (OutsideModel e) {
            return false;
        }
    }

    private static int skipBlanks(String s, int pos) {
        int at = pos;
        while (at < s.length() && isBlank(s.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}
