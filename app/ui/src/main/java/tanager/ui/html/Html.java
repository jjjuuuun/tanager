package tanager.ui.html;

import java.util.List;

/** Swing 의 HTML 글자(툴팁 · 렌더러)에 넣을 문장을 만든다. 경로 · 셸 이름의 {@code < > &} 가 태그로 읽히지 않게 한다. */
public final class Html {
    private Html() {}

    /** {@code & < >} 를 HTML 엔터티로. */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 줄마다 escape 해 {@code <br>} 로 이은 {@code <html>…</html>}. */
    public static String lines(List<String> lines) {
        StringBuilder html = new StringBuilder("<html>");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                html.append("<br>");
            }
            html.append(escape(lines.get(i)));
        }
        return html.append("</html>").toString();
    }
}
