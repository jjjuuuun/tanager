package dev.tanager.spike;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 세 셸(WSL bash · Windows PowerShell 5.1 · cmd)의 기동 명령과 셸 통합 스크립트. */
final class Shells {
    enum Kind { WSL_BASH, POWERSHELL, CMD }

    static final Path WORK = Path.of(System.getProperty("java.io.tmpdir"), "tanager-spike");

    private Shells() {}

    /** 셸 통합 스크립트를 임시 폴더에 쓴다. bash 는 LF, ps1 은 UTF-8 BOM + CRLF(5.1 이 한글을 깨지 않게). */
    static void installScripts() throws IOException {
        Files.createDirectories(WORK);
        String bash = """
                # Tanager 셸 통합 (스파이크)
                [ -f ~/.bashrc ] && . ~/.bashrc
                __tanager_osc7() {
                  local LC_ALL=C   # 바이트 단위로 돌아야 한글이 UTF-8 바이트별 %XX 가 된다
                  local i c out=""
                  for ((i=0; i<${#PWD}; i++)); do
                    c="${PWD:i:1}"
                    case "$c" in
                      [-/._~A-Za-z0-9]) out+="$c" ;;
                      *) printf -v c '%%%02X' "'$c"; out+="$c" ;;
                    esac
                  done
                  printf '\\e]7;file://%s%s\\a' "${HOSTNAME:-localhost}" "$out"
                }
                PROMPT_COMMAND="__tanager_osc7${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
                """;
        Files.writeString(WORK.resolve("tanager.bash"), bash, StandardCharsets.UTF_8);

        String ps1 = String.join("\r\n",
                "# Tanager 셸 통합 (스파이크) — Windows PowerShell 5.1 문법",
                "$global:__tanagerPrompt = $function:prompt",
                "function global:prompt {",
                "  $e = [char]27",
                "  $p = $ExecutionContext.SessionState.Path.CurrentLocation",
                "  if ($p.Provider.Name -eq 'FileSystem') { [Console]::Write(\"$e]9;9;`\"$($p.ProviderPath)`\"$e\\\") }",
                "  & $global:__tanagerPrompt",
                "}",
                "");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = ps1.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(WORK.resolve("tanager.ps1"), all);
    }

    /** C:\Users\x → /mnt/c/Users/x */
    static String toWsl(Path p) {
        String s = p.toAbsolutePath().toString().replace('\\', '/');
        if (s.length() > 2 && s.charAt(1) == ':') s = "/mnt/" + Character.toLowerCase(s.charAt(0)) + s.substring(2);
        return s;
    }

    static List<String> command(Kind kind) {
        return switch (kind) {
            case WSL_BASH -> List.of("wsl.exe", "--", "bash", "--rcfile", toWsl(WORK.resolve("tanager.bash")), "-i");
            case POWERSHELL -> List.of("powershell.exe", "-NoLogo", "-NoExit", "-ExecutionPolicy", "Bypass",
                    "-Command", ". '" + WORK.resolve("tanager.ps1") + "'");
            case CMD -> List.of("cmd.exe", "/k");
        };
    }

    static PtyProcess start(Kind kind, int cols, int rows) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        if (kind == Kind.CMD) env.put("PROMPT", "$e]9;9;$P$e\\$P$G");
        return new PtyProcessBuilder(command(kind).toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.home"))
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setUseWinConPty(true)
                .start();
    }

    /** wsl.exe --list --quiet 은 UTF-16LE 로 출력한다. */
    static List<String> wslDistros() {
        try {
            Process p = new ProcessBuilder("wsl.exe", "--list", "--quiet").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), Charset.forName("UTF-16LE"));
            p.waitFor();
            List<String> list = new ArrayList<>();
            for (String line : out.split("\\R")) if (!line.isBlank()) list.add(line.strip());
            return list;
        } catch (Exception e) {
            return List.of("오류: " + e);
        }
    }
}
