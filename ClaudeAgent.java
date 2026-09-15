import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class ClaudeAgent implements CodingAgent {

    private static final Path EMPTY_MCP_CONFIG = Path.of("/tmp/github-worker-empty-mcp.json");
    private static final Path LOG_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "claude.log");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String canary;

    ClaudeAgent(String canary) {
        this.canary = canary == null ? "" : canary.strip();
    }

    @Override
    public String run(String prompt, Path workDir, int timeoutMinutes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "claude", "-p",
                    "--dangerously-skip-permissions",
                    "--model", "sonnet");
            pb.directory(workDir.toFile());
            return execute(pb, prompt, timeoutMinutes, workDir.toString());
        } catch (Exception e) {
            System.err.println("  Claude failed: " + e.getMessage());
            appendLog("ERROR", e.getMessage());
            return null;
        }
    }

    @Override
    public String runBare(String prompt, int timeoutSeconds) {
        try {
            ensureEmptyMcpConfig();
            ProcessBuilder pb = new ProcessBuilder(
                    "claude", "-p",
                    "--model", "sonnet",
                    "--mcp-config", EMPTY_MCP_CONFIG.toString(),
                    "--strict-mcp-config");
            return execute(pb, prompt, timeoutSeconds / 60 + 1, "(bare)");
        } catch (Exception e) {
            System.err.println("  Claude (bare) failed: " + e.getMessage());
            appendLog("ERROR", e.getMessage());
            return null;
        }
    }

    private String execute(ProcessBuilder pb, String prompt, int timeoutMinutes, String context) {
        appendLog("START", "cwd=" + context + " timeout=" + timeoutMinutes + "m prompt=" + truncate(prompt, 200));

        pb.redirectErrorStream(false);
        // A review body is far larger than a pipe buffer, so this has to read
        // while the process runs rather than after it exits - see Exec.
        Exec.Result r = Exec.run(pb, timeoutMinutes, TimeUnit.MINUTES, prompt);

        if (r.timedOut()) {
            appendLog("TIMEOUT", "after " + timeoutMinutes + " minutes");
            return null;
        }
        if (r.exitCode() != 0) {
            appendLog("FAIL", "exit=" + r.exitCode() + " " + truncate(r.stderr(), 300));
            return null;
        }

        appendLog("OK", truncate(r.stdout(), 500));
        return stripCanary(r.stdout(), canary);
    }

    /**
     * The principal's global CLAUDE.md makes every reply open with a canary
     * word on its own line (CLAUDE_CANARY). --bare would skip CLAUDE.md but
     * also skips OAuth. Left in, a security verdict of "SAFE: ..." read as
     * SUSPICIOUS (86 of 276 logged replies carried it,
     * bin-space-microservices#532). Only a first line that IS the configured
     * word is removed - never an arbitrary one-word line - so the strict
     * verdict parser sees exactly what it did before, minus that one line.
     */
    static String stripCanary(String out, String canary) {
        if (out == null || canary == null || canary.isEmpty()) {
            return out;
        }
        String s = out.stripLeading();
        int nl = s.indexOf('\n');
        if (nl > 0 && s.substring(0, nl).strip().equals(canary)) {
            return s.substring(nl + 1).stripLeading();
        }
        return out;
    }

    private void appendLog(String level, String message) {
        try {
            String line = "[" + LocalDateTime.now().format(TS) + "] " + level + " " + message + "\n";
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void ensureEmptyMcpConfig() throws IOException {
        if (!Files.exists(EMPTY_MCP_CONFIG)) {
            Files.writeString(EMPTY_MCP_CONFIG, "{\"mcpServers\":{}}");
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
