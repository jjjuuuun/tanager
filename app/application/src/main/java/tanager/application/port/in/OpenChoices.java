package tanager.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ShellProfile;

/**
 * 폴더의 열기 메뉴 후보(#91, FR-HIS-06).
 *
 * @param primary "여기서 열기"가 쓸 셸(그 환경의 기본). 그 환경의 셸이 없으면(지운 배포판) empty
 * @param others "다른 프로파일로 열기" 후보: 같은 환경의 다른 셸과, 그 환경의 경로로 바꿀 수 있는 다른 환경의 셸(탐지 순서)
 */
public record OpenChoices(Optional<ShellProfile> primary, List<ShellProfile> others) {
    public OpenChoices {
        Objects.requireNonNull(primary, "primary");
        others = List.copyOf(others);
    }
}
