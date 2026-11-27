package tanager.application.port.in;

import java.util.List;
import tanager.domain.session.ShellProfile;

/** 띄울 수 있는 셸 프로파일 목록(FR-SES-01). */
public interface ListProfilesUseCase {

    /**
     * 탐지한 프로파일들(수정할 수 없는 목록, 탐지 순서). 첫 호출만 셸을 탐지하고 이후에는 같은 결과를 돌려준다. 동시에 불러도
     * 탐지는 한 번이다. 탐지 결과(실패로 비거나 일부만 찾았어도)는 앱이 도는 동안 그대로 쓴다. 첫 호출은 수 초 블로킹될 수 있어
     * EDT 에서 부르지 않는다.
     */
    List<ShellProfile> list();
}
