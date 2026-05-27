import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handlers for the {@code github.issue.*} elf-bus kinds.
 *
 * Each handler returns a JSON-serialisable Map; {@link ElfBusConsumer}
 * enqueues it as a {@code .result} envelope if the original message
 * set {@code X-Elf-Reply-To}.
 *
 * Error policy: {@link GitHubClient} returns null on failure with no
 * categorisation between transient and permanent. Until we plumb that
 * through, all client-side failures are treated as retryable — the
 * consumer's bounded retry budget ({@code X-Elf-Max-Attempts}, default
 * 5) prevents runaway retries on permanent failures (auth/404/422),
 * after which the message moves to {@code .dead/new/}.
 */
public final class GitHubIssueHandlers {

    private GitHubIssueHandlers() {}

    /** {@code github.issue.create} → {@code github.issue.create.result} */
    public static final class Create implements ElfBusHandler {
        private final GitHubClient gh;
        public Create(GitHubClient gh) { this.gh = gh; }
        @Override public String kind() { return "github.issue.create"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            JsonNode b = env.body();
            String repo  = requireText(b, "repo");
            String title = requireText(b, "title");
            String body  = requireText(b, "body");
            List<String> labels    = textArray(b, "labels");
            List<String> assignees = textArray(b, "assignees");

            String url = gh.createIssue(repo, title, body, labels, assignees);
            if (url == null) {
                throw new RetryableException("gh createIssue returned null for " + repo);
            }
            return Map.of(
                    "status", "ok",
                    "issue", Map.of(
                            "repo",   repo,
                            "number", extractIssueNumber(url),
                            "url",    url
                    )
            );
        }
    }

    /** {@code github.issue.comment} → {@code github.issue.comment.result} */
    public static final class Comment implements ElfBusHandler {
        private final GitHubClient gh;
        public Comment(GitHubClient gh) { this.gh = gh; }
        @Override public String kind() { return "github.issue.comment"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            JsonNode b = env.body();
            String repo = requireText(b, "repo");
            int    num  = requireInt(b, "num");
            String body = requireText(b, "body");

            Long commentId = gh.postComment(repo, num, body);
            if (commentId == null) {
                throw new RetryableException("gh postComment returned null for " + repo + "#" + num);
            }
            return Map.of(
                    "status", "ok",
                    "comment", Map.of("id", commentId)
            );
        }
    }

    /** {@code github.issue.label} → {@code github.issue.label.result} */
    public static final class Label implements ElfBusHandler {
        private final GitHubClient gh;
        public Label(GitHubClient gh) { this.gh = gh; }
        @Override public String kind() { return "github.issue.label"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            JsonNode b = env.body();
            String repo  = requireText(b, "repo");
            int    num   = requireInt(b, "num");
            String label = requireText(b, "label");

            if (!gh.addLabel(repo, num, label)) {
                throw new RetryableException("gh addLabel failed for " + repo + "#" + num);
            }
            return Map.of("status", "ok");
        }
    }

    // ---- helpers ----

    /** Issue URL is {@code https://github.com/owner/repo/issues/42}; we want 42. */
    private static int extractIssueNumber(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return 0;
        try { return Integer.parseInt(url.substring(slash + 1)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String requireText(JsonNode b, String field) throws IOException {
        JsonNode v = b.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank())
            throw new IOException("missing/empty required field: " + field);
        return v.asText();
    }

    private static int requireInt(JsonNode b, String field) throws IOException {
        JsonNode v = b.get(field);
        if (v == null || !v.canConvertToInt())
            throw new IOException("missing/invalid required int field: " + field);
        return v.asInt();
    }

    private static List<String> textArray(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || !v.isArray()) return List.of();
        List<String> out = new ArrayList<>(v.size());
        v.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
        return List.copyOf(out);
    }
}
