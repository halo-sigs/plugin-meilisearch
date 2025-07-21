package run.halo.meilisearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlUtilsTest {

    @Test
    void stripHtml_shouldRemoveHtmlTags() {
        String htmlContent = "<p>Hello <strong>world</strong>!</p>";
        String result = HtmlUtils.stripHtml(htmlContent);
        assertThat(result).isEqualTo("Hello world!");
    }

    @Test
    void stripHtml_shouldHandleComplexHtml() {
        String htmlContent = "<div><h1>Title</h1><p>Paragraph with <a href=\"#\">link</a> and <em>emphasis</em>.</p></div>";
        String result = HtmlUtils.stripHtml(htmlContent);
        assertThat(result).isEqualTo("Title Paragraph with link and emphasis.");
    }

    @Test
    void stripHtml_shouldReturnSameForPlainText() {
        String plainText = "This is plain text";
        String result = HtmlUtils.stripHtml(plainText);
        assertThat(result).isEqualTo(plainText);
    }

    @Test
    void stripHtml_shouldHandleNullInput() {
        String result = HtmlUtils.stripHtml(null);
        assertThat(result).isNull();
    }

    @Test
    void stripHtml_shouldHandleEmptyInput() {
        String result = HtmlUtils.stripHtml("");
        assertThat(result).isEmpty();
    }

    @Test
    void stripHtmlAndTrim_shouldRemoveHtmlAndTrimWhitespace() {
        String htmlContent = "  <p>  Hello <strong>world</strong>!  </p>  ";
        String result = HtmlUtils.stripHtmlAndTrim(htmlContent);
        assertThat(result).isEqualTo("Hello world!");
    }

    @Test
    void stripHtmlAndTrim_shouldHandleNullInput() {
        String result = HtmlUtils.stripHtmlAndTrim(null);
        assertThat(result).isNull();
    }
} 