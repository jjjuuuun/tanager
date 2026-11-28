package tanager.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tanager.application.port.out.ToolkitProvider;

/**
 * Tanager 의 아키텍처 규칙(ADR-0002, .claude/rules/architecture.md).
 * 기준 패키지를 받아 만들므로 실제 코드(tanager)와 위반 픽스처(tanager.archfixture.*)에 같은 규칙을 건다.
 */
final class ArchitectureRules {
    private ArchitectureRules() {}

    /**
     * domain 은 순수 Java: I/O · UI · 동시성 · 외부 라이브러리 금지. {@code java.nio} 중 {@code StandardCharsets} 만 허용한다 — OSC 7
     * 경로의 UTF-8 해석(ADR-0015).
     */
    static ArchRule domainIsPure(String base) {
        return noClasses()
                .that()
                .resideInAPackage(base + ".domain..")
                .should()
                .dependOnClassesThat(resideInAPackage("java.nio..").and(not(name(StandardCharsets.class.getName()))))
                .orShould()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.io..",
                        "java.net..",
                        "java.sql..",
                        "java.awt..",
                        "javax.swing..",
                        "java.util.concurrent..",
                        "com.pty4j..",
                        "com.jediterm..",
                        "tools.jackson..",
                        "com.formdev..")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(Thread.class)
                .because("domain 은 순수 Java 여야 한다 — I/O · UI · 스레드는 어댑터 · 플랫폼 · StateActor 의 일");
    }

    /** domain ← application ← (adapter · platform · ui) ← bootstrap. */
    static ArchRule layersRespectDirection(String base) {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Domain")
                .definedBy(base + ".domain..")
                .layer("Application")
                .definedBy(base + ".application..")
                .layer("Adapter")
                .definedBy(base + ".adapter..")
                .layer("Platform")
                .definedBy(base + ".platform..")
                .layer("Ui")
                .definedBy(base + ".ui..")
                .layer("Bootstrap")
                .definedBy(base + ".bootstrap..")
                .whereLayer("Bootstrap")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapter")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Platform")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Ui")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Adapter", "Platform", "Ui", "Bootstrap")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Adapter", "Platform", "Ui", "Bootstrap")
                .because("의존 방향은 ADR-0002 — 바깥이 안쪽만 안다");
    }

    /** 어댑터끼리는 서로 모른다(application 의 포트로만 이어진다). */
    static ArchRule adaptersAreIndependent(String base) {
        return slices().matching(base + ".adapter.(*)..").should().notDependOnEachOther();
    }

    /** domain 의 바운디드 컨텍스트 패키지(history · session · layout …)끼리 순환 의존하지 않는다(history 는 session 을 모른다). */
    static ArchRule domainPackagesAreFreeOfCycles(String base) {
        return slices().matching(base + ".domain.(*)..")
                .should()
                .beFreeOfCycles()
                .because("컨텍스트 의존은 한 방향이다 — session → history, history 는 session 을 import 하지 않는다");
    }

    /**
     * application 의 패키지(port.in · port.out · state · service …, 하위 패키지마다 한 조각)끼리 순환 의존하지 않는다. 이미 있는
     * port.out → platform 간선({@code PlatformModule} → {@code OperatingSystem}, platform 의 {@code PlatformSelection} 이
     * port.out 을 쓴다)은 따로 정리할 때까지 뺀다.
     */
    static ArchRule applicationPackagesAreFreeOfCycles(String base) {
        return slices().matching(base + ".application.(**)")
                .should()
                .beFreeOfCycles()
                .ignoreDependency(
                        resideInAPackage(base + ".application.port.out.."),
                        resideInAPackage(base + ".application.platform.."))
                .because("state 는 포트를 알고 포트는 state 를 모른다 — 의존은 한 방향(ADR-0012)");
    }

    /** ui 는 application 의 포트만 쓴다. StateActor 같은 구현은 bootstrap 이 조립해 포트로 넘긴다(ADR-0012). */
    static ArchRule uiUsesOnlyApplicationPorts(String base) {
        return noClasses()
                .that()
                .resideInAPackage(base + ".ui..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(base + ".application.state..")
                .because("ui 는 application.port 만 본다 — 상태 변경 경로는 포트 하나로 모은다");
    }

    /** OS 별 플랫폼 모듈끼리는 서로 모른다(macOS 추가 시 다른 모듈을 바꾸지 않기 위해, NFR-04). */
    static ArchRule platformsAreIndependent(String base) {
        return slices().matching(base + ".platform.(*)..").should().notDependOnEachOther();
    }

    /** pty4j 는 pty 어댑터만 쓴다. UI · application 은 TerminalStream 포트로만 터미널을 만진다(ADR-0007). */
    static ArchRule onlyPtyAdapterUsesPty4j(String base) {
        return noClasses()
                .that()
                .resideOutsideOfPackage(base + ".adapter.pty..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.pty4j..")
                .because("pty4j 는 adapter.pty 안에 가둔다 — 나머지는 TerminalStream 포트만 본다");
    }

    /**
     * OS 판별은 bootstrap 의 플랫폼 선택 한 곳에서만 한다(architecture.md). 클래스패스에 있는 라이브러리의 OS 판별
     * ({@code com.sun.jna.Platform}, FlatLaf {@code SystemInfo})도 bootstrap 밖에서는 쓰지 않는다. {@code "os.name"}
     * 문자열은 {@link OsNameSourceTest} 가 소스에서 본다.
     */
    static ArchRule onlyBootstrapDetectsOs(String base) {
        return noClasses()
                .that()
                .resideOutsideOfPackage(base + ".bootstrap..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.sun.jna.Platform")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.formdev.flatlaf.util.SystemInfo")
                .because("OS 분기는 bootstrap 의 플랫폼 선택(ServiceLoader) 한 곳에만 둔다 — 나머지는 PlatformModule 포트만 본다");
    }

    /**
     * 도구함 공급자({@link ToolkitProvider}) 구현은 toolkit 어댑터({@code adapter.toolkit..})에만 둔다(ADR-0019). 새 AI 도구는 어댑터
     * 모듈 하나로 붙고, 그 밖의 계층이 공급자를 몰래 만들어 등록하지 않게 한다.
     */
    static ArchRule toolkitProvidersLiveInToolkitAdapters(String base) {
        return classes()
                .that()
                .implement(ToolkitProvider.class)
                .should()
                .resideInAPackage(base + ".adapter.toolkit..")
                .because("새 AI 도구 = app/adapter/toolkit/<도구> 모듈 + ToolkitProvider 등록(architecture.md)");
    }

    /** 색은 ui.theme 의 토큰에서만 온다. 그 밖의 ui 코드는 색을 만들지 않는다(SDD §8.5, ADR-0010). */
    static ArchRule colorsOnlyFromThemeTokens(String base) {
        return classes()
                .that()
                .resideInAPackage(base + ".ui..")
                // 허용은 ui.theme 패키지 자체뿐이다(하위 패키지 제외)
                .and()
                .resideOutsideOfPackage(base + ".ui.theme")
                .should(new NotCreateColors(base + ".ui.theme.Rgb"))
                .because("색 리터럴은 테마 토큰(themes/*.properties)에만 둔다 — 대비는 SoftThemeTokensTest 가 검사");
    }

    /**
     * 색 타입의 생성 · 상수 · 정적 팩터리 · 파생(brighter/darker)과 {@code Rgb} 생성 · 파싱을 위반으로 본다.
     * 호출과 참조({@code Color::new}, {@code Color::decode}) 모두. 받은 색을 읽는 인스턴스 메서드는 허용한다.
     */
    private static final class NotCreateColors extends ArchCondition<JavaClass> {
        private static final Set<String> COLOR_TYPES = Set.of(
                "java.awt.Color",
                "java.awt.SystemColor",
                "javax.swing.plaf.ColorUIResource",
                "com.formdev.flatlaf.util.HSLColor",
                "com.jediterm.core.Color",
                "com.jediterm.terminal.TerminalColor",
                "com.jediterm.terminal.emulator.ColorPaletteImpl");
        /** 인스턴스 메서드지만 새 색을 만든다. */
        private static final Set<String> DERIVING_METHODS = Set.of("brighter", "darker");
        /** 정적 여부를 클래스패스에서 못 읽을 때를 위한 알려진 팩터리 이름. */
        private static final Set<String> KNOWN_FACTORIES =
                Set.of("decode", "getColor", "getHSBColor", "rgb", "color", "fromColor", "toRGB");
        /** ANSI 번호로 가리킬 뿐 색 값을 만들지 않는다. */
        private static final String ALLOWED_STATIC = "com.jediterm.terminal.TerminalColor.index";

        private final String rgbType;

        NotCreateColors(String rgbType) {
            super("not create colors");
            this.rgbType = rgbType;
        }

        @Override
        public void check(JavaClass item, ConditionEvents events) {
            List<JavaAccess<?>> constructors = new ArrayList<>(item.getConstructorCallsFromSelf());
            constructors.addAll(item.getConstructorReferencesFromSelf());
            for (JavaAccess<?> access : constructors) {
                String owner = access.getTargetOwner().getName();
                if (COLOR_TYPES.contains(owner) || owner.equals(rgbType)) {
                    events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                }
            }
            for (JavaAccess<?> access : item.getFieldAccessesFromSelf()) {
                if (COLOR_TYPES.contains(access.getTargetOwner().getName())) {
                    events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                }
            }
            List<JavaCodeUnitAccess<?>> methods = new ArrayList<>(item.getMethodCallsFromSelf());
            methods.addAll(item.getMethodReferencesFromSelf());
            for (JavaCodeUnitAccess<?> access : methods) {
                if (createsColor(access)) {
                    events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                }
            }
        }

        private boolean createsColor(JavaCodeUnitAccess<?> access) {
            String owner = access.getTargetOwner().getName();
            String name = access.getName();
            if (owner.equals(rgbType)) {
                return name.equals("parse");
            }
            if (!COLOR_TYPES.contains(owner) || (owner + "." + name).equals(ALLOWED_STATIC)) {
                return false;
            }
            boolean isStatic = access.getTarget()
                    .resolveMember()
                    .map(m -> m.getModifiers().contains(JavaModifier.STATIC))
                    .orElse(false);
            return isStatic || DERIVING_METHODS.contains(name) || KNOWN_FACTORIES.contains(name);
        }
    }
}
