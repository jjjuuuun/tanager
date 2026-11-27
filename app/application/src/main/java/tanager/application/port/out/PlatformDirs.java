package tanager.application.port.out;

import java.nio.file.Path;

/** 앱이 쓰는 OS 별 폴더(SDD §4.1). 경로를 계산만 하고 디렉터리를 만들지 않는다. */
public interface PlatformDirs {

    /** 설정 · 상태 파일을 두는 앱 데이터 폴더. 예: Windows {@code %APPDATA%\Tanager}. */
    Path appData();
}
