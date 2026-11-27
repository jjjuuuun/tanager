package tanager.application.port.in;

import java.util.List;
import tanager.domain.session.ShellProfile;

/**
 * 셸 탐지가 끝났음을 알린다. 탐지 순서가 트리의 환경 노드 순서가 된다(#83). 탐지는 수 초 걸릴 수 있어 트리는 EDT 에서 탐지를 기다리지
 * 않고, 이 알림 전에는 이력의 환경을 이름순으로 놓는다.
 */
public interface RecordEnvironmentsUseCase {

    /** {@code profiles} 의 환경을 처음 나온 순서로 한 번씩 기록한다. 순서가 바뀌었으면 {@code EnvironmentsChanged} 가 나간다. */
    void profilesDetected(List<ShellProfile> profiles);
}
