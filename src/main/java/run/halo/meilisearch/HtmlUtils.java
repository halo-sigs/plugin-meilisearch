package run.halo.meilisearch;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

public final class HtmlUtils {

    private HtmlUtils() {
    }

    public static String stripHtml(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return Jsoup.parse(content).text();
    }

    public static String stripHtmlAndTrim(String content) {
        String cleaned = stripHtml(content);
        return cleaned != null ? cleaned.trim() : null;
    }
} 