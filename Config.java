import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "github-worker");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");
    static final Path STATE_PATH = CONFIG_DIR.resolve("state.json");
    static final Path LOCK_PATH = CONFIG_DIR.resolve("lock");
    static final Path BUS_ROOT_DEFAULT = Path.of(
            System.getProperty("user.home"), ".local", "share", "elf-bus");

    String githubUser;
    String githubToken;
    String botUser;
    String botToken;
    Set<String> excludeOrgs;
    String gmailAddress;
    /** The account SMTP AUTH runs as - Gmail rejects an alias here. */
    String gmailAuthUser;
    String gmailAppPassword;
    String sendTo;
    String activeHours;
    Path workDir;
    int lookbackDays;
    String agent;
    boolean emailNotifications;
    Set<String> topics;
    Set<String> orgs;
    Path busRoot;
    /** Whether a review may push fixes and merge, or only comment. */
    boolean reviewAutoFix;
    boolean reviewAutoMerge;
    /** Finding severities a review will act on; anything else stays a comment. */
    Set<String> reviewFixSeverities;
    /**
     * GitHub logins whose 👍/👎/comment settles an AWAITING_APPROVAL issue.
     * More than the principal now: a product owner who is not a developer can
     * approve their own request without going through him.
     */
    Set<String> approvers;
    /**
     * GitHub logins whose issues we pick up even when unassigned. Discovery is
     * otherwise "assignee:<principal>", which a non-developer filing a request
     * has no reason to know about - their issue would sit looking accepted.
     */
    Set<String> requesters;

    /** Prefix marking a config value that lives in GCP Secret Manager. */
    static final String SECRET_PREFIX = "sm://";
    static final String SECRET_PROJECT = "bin-space-microservices";

    /**
     * Resolves {@code sm://secret-name} to the secret's latest version, so
     * credentials live in Secret Manager rather than in plaintext on disk.
     * Anything without the prefix is returned as-is, which keeps the config
     * format backwards compatible.
     *
     * <p>Shells out to gcloud rather than pulling in the client library: this
     * runs on a workstation that already has an authenticated gcloud, and a
     * jbang single-file script should not grow a dependency tree for one call.
     *
     * <p>Fails loudly. A silently-empty credential here would show up as a
     * confusing auth error hours later in an unrelated part of the run.
     */
    private static String resolveSecret(String value) {
        if (value == null || !value.startsWith(SECRET_PREFIX)) {
            return value;
        }
        String name = value.substring(SECRET_PREFIX.length()).strip();
        try {
            Process p = new ProcessBuilder("gcloud", "secrets", "versions", "access", "latest",
                    "--secret=" + name, "--project=" + SECRET_PROJECT)
                    .redirectErrorStream(false)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
                String err = new String(p.getErrorStream().readAllBytes()).strip();
                System.err.println("Failed to resolve " + value + " from Secret Manager: " + err);
                System.exit(1);
            }
            return out.strip();
        } catch (Exception e) {
            System.err.println("Failed to resolve " + value + ": " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run install.sh to set up github-worker.");
            System.exit(1);
        }

        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    raw.put(line.substring(0, eq).strip(),
                            resolveSecret(line.substring(eq + 1).strip()));
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.githubToken = raw.getOrDefault("GITHUB_TOKEN", "");
        c.botUser = raw.getOrDefault("BOT_USER", "");
        c.botToken = raw.getOrDefault("BOT_TOKEN", "");
        c.excludeOrgs = parseSet(raw.getOrDefault("EXCLUDE_ORGS", ""));
        c.gmailAddress = raw.getOrDefault("GMAIL_ADDRESS", "");
        // Gmail authenticates the mailbox, not the alias: sending as
        // bin.chicken@bin-space.app while authenticating as that name gets
        // "535-5.7.8 Username and Password not accepted", which reads as a bad
        // password rather than the wrong account. Defaults to GMAIL_ADDRESS so
        // a config without this key behaves as before.
        c.gmailAuthUser = raw.getOrDefault("GMAIL_AUTH_USER", c.gmailAddress);
        // Google displays app passwords in four spaced groups; the spaces are
        // presentation only and SMTP AUTH wants the bare 16 characters. Storing
        // it as displayed is the obvious mistake, and it fails as
        // "535 Username and Password not accepted" - indistinguishable from a
        // wrong password.
        c.gmailAppPassword = raw.getOrDefault("GMAIL_APP_PASSWORD", "").replaceAll("\\s", "");
        c.sendTo = raw.getOrDefault("SEND_TO", "");
        c.activeHours = raw.getOrDefault("ACTIVE_HOURS", "08-18");
        c.workDir = Path.of(raw.getOrDefault("WORK_DIR", "/tmp/github-worker"));
        c.lookbackDays = Integer.parseInt(raw.getOrDefault("LOOKBACK_DAYS", "7"));
        c.agent = raw.getOrDefault("AGENT", "claude");
        c.emailNotifications = !"false".equalsIgnoreCase(raw.getOrDefault("EMAIL_NOTIFICATIONS", "true"));
        c.reviewAutoFix = !"false".equalsIgnoreCase(raw.getOrDefault("REVIEW_AUTO_FIX", "true"));
        c.reviewAutoMerge = !"false".equalsIgnoreCase(raw.getOrDefault("REVIEW_AUTO_MERGE", "true"));
        c.reviewFixSeverities = parseSet(
                raw.getOrDefault("REVIEW_FIX_SEVERITIES", "CRITICAL,SUGGESTION"))
                .stream().map(String::toUpperCase).collect(Collectors.toSet());
        // The principal always approves, whatever else is configured - losing
        // that to a typo would strand every issue in AWAITING_APPROVAL.
        c.approvers = new java.util.HashSet<>(parseSet(raw.getOrDefault("APPROVERS", "")));
        c.approvers.add(c.githubUser);
        c.requesters = parseSet(raw.getOrDefault("REQUESTERS", ""));
        c.topics = parseSet(raw.getOrDefault("TOPICS", ""));
        c.orgs = parseSet(raw.getOrDefault("ORGS", ""));
        c.busRoot = Path.of(raw.getOrDefault("BUS_ROOT", BUS_ROOT_DEFAULT.toString()));
        return c;
    }

    Path busRoot()        { return busRoot; }
    Path busSeenIdsPath() { return CONFIG_DIR.resolve("elf-bus-seen"); }

    void validate(boolean requireEmail) {
        requireField("GITHUB_USER", githubUser);
        requireField("GITHUB_TOKEN", githubToken);
        requireField("BOT_USER", botUser);
        requireField("BOT_TOKEN", botToken);
        if (requireEmail && emailNotifications) {
            requireField("GMAIL_ADDRESS", gmailAddress);
            requireField("GMAIL_APP_PASSWORD", gmailAppPassword);
            requireField("SEND_TO", sendTo);
        }
    }

    boolean isWithinActiveHours() {
        try {
            String[] parts = activeHours.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            int hour = LocalTime.now().getHour();
            return start <= hour && hour < end;
        } catch (Exception e) {
            return true;
        }
    }

    private static Set<String> parseSet(String csv) {
        return Set.of(csv.split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isEmpty() || "CHANGE_ME".equals(value)) {
            System.err.println("Error: " + name + " not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }
}
