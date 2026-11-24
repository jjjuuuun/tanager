package dev.tanager.spike;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** pty4j 프로세스를 JediTerm 에 잇고, 화면에 그리기 전에 OSC 로 cwd 를 가로챈다(확인 항목 c). */
final class SpikeConnector extends ProcessTtyConnector {
    private final String name;
    private final OscScanner scanner;
    private final Consumer<String> log;

    SpikeConnector(String name, PtyProcess process, Consumer<String> onCwd, Consumer<String> log) {
        super(process, StandardCharsets.UTF_8);
        this.name = name;
        this.scanner = new OscScanner(onCwd);
        this.log = log;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int n = super.read(buf, offset, length);
        if (n > 0) scanner.feed(buf, offset, n);
        return n;
    }

    @Override
    public void resize(TermSize size) {
        if (getProcess() instanceof PtyProcess pty && isConnected()) {
            pty.setWinSize(new WinSize(size.getColumns(), size.getRows()));
            log.accept(name + " resize " + size.getColumns() + "x" + size.getRows());
        }
    }

    @Override
    public String getName() { return name; }
}
