package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.in.QueryToolkitUseCase.Freshness;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitView;
import tanager.domain.toolkit.ToolkitWarning;

/** 조립이 도구함 공급자를 {@code ServiceLoader} 로 찾아 조회에 쓴다(#209). 공급자는 테스트 리소스로 등록한 {@link EchoToolkitProvider}. */
class AssemblyToolkitTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();

    @TempDir
    Path appData;

    private final List<Assembly> assemblies = new ArrayList<>();

    @AfterEach
    void shutdownAssemblies() {
        assemblies.forEach(Assembly::shutdown);
    }

    private Assembly assemble() {
        FakePlatformModule platform = new FakePlatformModule(List.of()).withAppData(appData);
        Assembly assembly = Assembly.assemble(platform, new ScriptedPtyLauncher(), new DirectUiDispatcher());
        assemblies.add(assembly);
        return assembly;
    }

    @Test
    @DisplayName("ServiceLoader 로 등록된 공급자를 찾아 그 환경의 칩으로 준다")
    void findsRegisteredProviders() {
        // when
        Assembly assembly = assemble();

        // then
        assertThat(assembly.toolkit().providers(WIN)).contains(EchoToolkitProvider.ID);
        assertThat(assembly.toolkit().providers(new ShellEnvironment.Wsl("Ubuntu")))
                .doesNotContain(EchoToolkitProvider.ID);
    }

    @Test
    @DisplayName("조회는 찾은 공급자의 항목을 담고, 홈을 모르면 HomeUnknown 경고를 싣는다")
    void queriesFoundProvidersInBackground() throws Exception {
        // given
        Assembly assembly = assemble();
        WorkspacePath work = WorkspacePath.of(WIN, "C:\\work").value().orElseThrow();

        // when
        ToolkitView view = assembly.toolkit()
                .query(work, Freshness.FORCE)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        // then
        assertThat(view.items())
                .filteredOn(item -> item.provider().equals(EchoToolkitProvider.ID))
                .extracting(ToolkitItem::name)
                .containsExactly("echo");
        assertThat(view.warnings()).contains(new ToolkitWarning.HomeUnknown(WIN));
    }
}
