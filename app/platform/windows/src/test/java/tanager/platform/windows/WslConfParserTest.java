package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.WslMount;

/** {@code /etc/wsl.conf} 의 {@code [automount] root} 읽기(#53). */
class WslConfParserTest {

    @Test
    @DisplayName("[automount] 의 root 를 마운트 루트로 읽는다")
    void readsAutomountRoot() {
        // when
        var mount = WslConfParser.automountRoot("[automount]\nroot = /win/\n");

        // then
        assertThat(mount).contains(new WslMount("/win/"));
    }

    @Test
    @DisplayName("끝 / 가 없거나 따옴표로 감싸도 같은 루트다")
    void normalizesTrailingSlashAndQuotes() {
        assertThat(WslConfParser.automountRoot("[automount]\nroot=/win")).contains(new WslMount("/win/"));
        assertThat(WslConfParser.automountRoot("[automount]\nroot = \"/win/\"")).contains(new WslMount("/win/"));
        assertThat(WslConfParser.automountRoot("[automount]\nroot = /")).contains(new WslMount("/"));
    }

    @Test
    @DisplayName("다른 섹션의 root · 주석 줄 · 줄 끝 주석은 무시하고 마지막 값을 쓴다")
    void ignoresOtherSectionsAndComments() {
        // given
        String conf = "[boot]\nroot = /x/\n[automount]\n# root = /y/\n; root = /q/\nenabled = true\n"
                + "root = /w/\nroot = /z/ # 주석\n[network]\nroot = /n/\n";

        // when
        var mount = WslConfParser.automountRoot(conf);

        // then
        assertThat(mount).contains(new WslMount("/z/"));
    }

    @Test
    @DisplayName("BOM 과 CRLF 줄바꿈을 견딘다")
    void toleratesBomAndCrlf() {
        assertThat(WslConfParser.automountRoot("﻿[automount]\r\nroot = /win/\r\n"))
                .contains(new WslMount("/win/"));
    }

    @Test
    @DisplayName("설정이 없거나 절대 경로가 아니면 empty — 호출 쪽이 기본값 /mnt/ 를 쓴다")
    void emptyWhenAbsentOrInvalid() {
        assertThat(WslConfParser.automountRoot("")).isEmpty();
        assertThat(WslConfParser.automountRoot("[boot]\nsystemd=true\n")).isEmpty();
        assertThat(WslConfParser.automountRoot("[automount]\nenabled = true\n")).isEmpty();
        assertThat(WslConfParser.automountRoot("[automount]\nroot = win/\n")).isEmpty();
        assertThat(WslConfParser.automountRoot("[automount]\nroot =\n")).isEmpty();
        assertThat(WslConfParser.automountRoot("root = /win/\n")).isEmpty();
    }
}
