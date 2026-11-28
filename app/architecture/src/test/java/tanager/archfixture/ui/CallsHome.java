package tanager.archfixture.ui;

import java.net.http.HttpClient;

/** 위반 픽스처: 앱이 HTTP 로 밖에 나간다(NFR-06). */
public final class CallsHome {
    Object client() {
        return HttpClient.newHttpClient();
    }
}
