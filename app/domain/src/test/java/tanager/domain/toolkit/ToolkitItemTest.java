package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static tanager.domain.toolkit.ToolkitFixtures.CLAUDE;
import static tanager.domain.toolkit.ToolkitFixtures.PROJECT_DIR;
import static tanager.domain.toolkit.ToolkitFixtures.SOURCE;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 도구함 항목 · 속성 값의 불변식과 마스킹(FR-TK-02 · FR-TK-07, #204). */
class ToolkitItemTest {

    private static ToolkitItem named(String name) {
        return new ToolkitItem(
                CLAUDE, ToolkitKind.SKILL, name, ToolkitScope.PROJECT, Optional.empty(), SOURCE, List.of(), false);
    }

    private static ToolkitItem withAttributes(List<Attribute> attributes) {
        return new ToolkitItem(
                CLAUDE,
                ToolkitKind.MCP_SERVER,
                "github",
                ToolkitScope.USER,
                Optional.empty(),
                SOURCE,
                attributes,
                false);
    }

    @Test
    @DisplayName("이름의 앞뒤 공백은 벗겨서 담는다")
    void stripsName() {
        // when
        ToolkitItem item = named("  tdd ");

        // then
        assertThat(item.name()).isEqualTo("tdd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", "", "t\u0000dd", "t\ndd", "t\u007fdd"})
    @DisplayName("빈 이름 · 제어 문자가 든 이름은 거절한다")
    void rejectsBlankOrControlName(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> named(name));
    }

    @Test
    @DisplayName("이름은 200 코드 포인트까지 받고 201 은 거절한다")
    void nameLengthLimit() {
        // given
        String max = "가".repeat(200);
        String surrogates = "😀".repeat(200);

        // then
        assertThat(named(max).name()).isEqualTo(max);
        assertThat(named(surrogates).name()).isEqualTo(surrogates);
        assertThatIllegalArgumentException().isThrownBy(() -> named("a".repeat(201)));
    }

    @Test
    @DisplayName("설명은 1000 코드 포인트까지 받는다")
    void descriptionLengthLimit() {
        // given
        String max = "d".repeat(1000);

        // when
        ToolkitItem ok = new ToolkitItem(
                CLAUDE, ToolkitKind.SKILL, "tdd", ToolkitScope.PROJECT, Optional.of(max), SOURCE, List.of(), false);

        // then
        assertThat(ok.description()).contains(max);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolkitItem(
                        CLAUDE,
                        ToolkitKind.SKILL,
                        "tdd",
                        ToolkitScope.PROJECT,
                        Optional.of(max + "d"),
                        SOURCE,
                        List.of(),
                        false));
    }

    @Test
    @DisplayName("속성 key 가 겹치면 거절한다")
    void rejectsDuplicateAttributeKeys() {
        // given
        List<Attribute> attributes = List.of(
                new Attribute("command", new AttributeValue.Plain("npx")),
                new Attribute("command", new AttributeValue.Plain("node")));

        // then
        assertThatIllegalArgumentException().isThrownBy(() -> withAttributes(attributes));
    }

    @Test
    @DisplayName("속성은 순서를 지키고 바깥 목록이 바뀌어도 따라 바뀌지 않는다")
    void attributesAreCopiedInOrder() {
        // given
        List<Attribute> attributes = new ArrayList<>(List.of(
                new Attribute("type", new AttributeValue.Plain("stdio")),
                new Attribute("command", new AttributeValue.Plain("npx"))));

        // when
        ToolkitItem item = withAttributes(attributes);
        attributes.clear();

        // then
        assertThat(item.attributes()).extracting(Attribute::key).containsExactly("type", "command");
    }

    @Test
    @DisplayName("속성 key 가 비었거나 제어 문자가 있으면 거절한다")
    void rejectsBadAttributeKey() {
        AttributeValue masked = new AttributeValue.Masked();
        assertThatIllegalArgumentException().isThrownBy(() -> new Attribute(" ", masked));
        assertThatIllegalArgumentException().isThrownBy(() -> new Attribute("A\nB", masked));
    }

    @Test
    @DisplayName("일반 값은 500 코드 포인트까지 받고 제어 문자 · 501 은 거절한다")
    void plainValueInvariants() {
        assertThat(new AttributeValue.Plain("x".repeat(500)).text()).hasSize(500);
        assertThatIllegalArgumentException().isThrownBy(() -> new AttributeValue.Plain("x".repeat(501)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AttributeValue.Plain("a\tb"));
    }

    @Test
    @DisplayName("Masked 에는 값을 담을 필드가 없어 toString 에 키만 보이고 비밀값은 나올 수 없다")
    void maskedHasNoValue() {
        // given
        ToolkitItem item = withAttributes(List.of(new Attribute("API_KEY", new AttributeValue.Masked())));
        ToolkitView view = new ToolkitView(PROJECT_DIR, List.of(item), List.of());

        // when
        String itemText = item.toString();
        String viewText = view.toString();

        // then
        List<Field> instanceFields = Arrays.stream(AttributeValue.Masked.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .toList();
        assertThat(instanceFields).isEmpty();
        assertThat(AttributeValue.Masked.class.getRecordComponents()).isEmpty();
        assertThat(itemText).contains("API_KEY").contains("Masked[]");
        assertThat(viewText).contains("API_KEY").contains("Masked[]");
    }

    @Test
    @DisplayName("shadowed 만 바꾼 새 항목을 돌려준다")
    void withShadowed() {
        // given
        ToolkitItem item = named("tdd");

        // when
        ToolkitItem shadowed = item.withShadowed(true);

        // then
        assertThat(shadowed.shadowed()).isTrue();
        assertThat(item.shadowed()).isFalse();
        assertThat(shadowed.withShadowed(false)).isEqualTo(item);
    }
}
