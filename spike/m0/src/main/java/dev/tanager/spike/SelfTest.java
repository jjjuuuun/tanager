package dev.tanager.spike;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 화면 없이 세 셸을 띄워 자동으로 확인한다(확인 항목 a · c, 한글 입출력 왕복).
 * 결과는 %TEMP%\tanager-spike\selftest.txt (jpackage exe 는 콘솔이 없으므로 파일로).
 */
final class SelfTest {
    private static final Duration WAIT = Duration.ofSeconds(25);
    private final List<String> report = new ArrayList<>();
    private int fails;

    static int run() throws Exception {
        SelfTest t = new SelfTest();
        Shells.installScripts();
        Path korean = Files.createDirectories(Shells.WORK.resolve("한글 폴더 #1"));
        t.line("java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        t.line("wsl --list --quiet (UTF-16LE 디코딩) = " + Shells.wslDistros());
        for (Shells.Kind k : Shells.Kind.values()) {
            try {
                t.shell(k, korean);
            } catch (Exception e) {
                t.check(k + " 예외", false, e.toString());
            }
        }
        t.line(t.fails == 0 ? "RESULT PASS" : "RESULT FAIL (" + t.fails + ")");
        Files.write(Shells.WORK.resolve("selftest.txt"), t.report, StandardCharsets.UTF_8);
        return t.fails;
    }

    private void shell(Shells.Kind kind, Path korean) throws Exception {
        BlockingQueue<String> cwds = new LinkedBlockingQueue<>();
        StringBuffer out = new StringBuffer();
        OscScanner scanner = new OscScanner(cwds::add);
        PtyProcess p = Shells.start(kind, 80, 24);
        line("== " + kind + " 프로세스 클래스 = " + p.getClass().getName());
        check(kind + " ConPTY 사용", p.getClass().getName().toLowerCase().contains("conpty"), p.getClass().getSimpleName());
        Thread reader = Thread.ofVirtual().start(() -> pump(p.getInputStream(), out, scanner));
        OutputStream in = p.getOutputStream();

        String first = cwds.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        check(kind + " 첫 프롬프트 OSC", first != null, String.valueOf(first));

        String target = kind == Shells.Kind.WSL_BASH ? Shells.toWsl(korean) : korean.toString();
        String cd = switch (kind) {
            case WSL_BASH -> "cd '" + target + "'";
            case POWERSHELL -> "cd '" + target + "'";
            case CMD -> "cd /d \"" + target + "\"";
        };
        send(in, cd);
        String after = waitFor(cwds, target);
        check(kind + " 한글 경로 cd 후 OSC", target.equals(after), "기대 " + target + " / 받음 " + after);

        send(in, "echo 한글입력확인ABC");
        check(kind + " 한글 입출력 왕복(UTF-8)", waitText(out, "한글입력확인ABC", 2), "출력에 2번 나타나야 함(입력 에코 + echo 결과)");

        p.setWinSize(new WinSize(100, 30));
        String probe = switch (kind) {
            case WSL_BASH -> "echo SIZE=$(tput cols)x$(tput lines)";
            case POWERSHELL -> "echo (\"SIZE=\" + $Host.UI.RawUI.WindowSize.Width + \"x\" + $Host.UI.RawUI.WindowSize.Height)";
            // mode con 은 ConPTY 아래에서 아무것도 출력하지 않았다(1차 실행) → 같은 콘솔을 쓰는 자식 프로세스로 크기 확인
            case CMD -> "powershell.exe -NoProfile -Command \"'SIZE=' + [Console]::WindowWidth + 'x' + [Console]::WindowHeight\"";
        };
        send(in, probe);
        boolean resized = waitText(out, "SIZE=100x30", 1);
        check(kind + " 창 크기 변경 전달(100x30)", resized, "SIZE=100x30");
        if (!resized) {
            String o = out.toString().replaceAll("\u001b\\[[0-9;?]*[A-Za-z]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)", "");
            line("  출력 끝부분: " + o.substring(Math.max(0, o.length() - 500)).replace("\r", "").replace("\n", " ⏎ "));
        }

        send(in, "exit");
        boolean exited = p.waitFor(10, TimeUnit.SECONDS);
        check(kind + " 종료", exited, exited ? "exit=" + p.exitValue() : "10초 안에 안 끝남");
        if (!exited) p.destroyForcibly();
        reader.join(2000);
    }

    private static void pump(InputStream is, StringBuffer out, OscScanner scanner) {
        byte[] b = new byte[8192];
        var dec = StandardCharsets.UTF_8.newDecoder();
        var bb = java.nio.ByteBuffer.allocate(16384);
        var cb = java.nio.CharBuffer.allocate(16384);
        try {
            int n;
            while ((n = is.read(b)) > 0) {
                bb.put(b, 0, n).flip();
                dec.decode(bb, cb, false);
                bb.compact();
                cb.flip();
                String s = cb.toString();
                cb.clear();
                out.append(s);
                scanner.feed(s);
            }
        } catch (Exception ignored) {
            // 프로세스 종료
        }
    }

    private static void send(OutputStream in, String cmd) throws Exception {
        Thread.sleep(300);
        in.write((cmd + "\r").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    private static String waitFor(BlockingQueue<String> q, String want) throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        String last = null;
        while (System.nanoTime() < end) {
            String c = q.poll(500, TimeUnit.MILLISECONDS);
            if (c != null) {
                last = c;
                if (c.equals(want)) return c;
            }
        }
        return last;
    }

    private static boolean waitText(StringBuffer out, String text, int times) throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < end) {
            if (count(out.toString(), text) >= times) return true;
            Thread.sleep(200);
        }
        return false;
    }

    private static int count(String s, String t) {
        int c = 0;
        for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + 1)) c++;
        return c;
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok) fails++;
        line((ok ? "PASS " : "FAIL ") + name + " — " + detail);
    }

    private void line(String s) {
        report.add(s);
        System.out.println(s);
    }
}
