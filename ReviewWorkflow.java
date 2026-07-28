import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;

public class ReviewWorkflow {

    private final GitHubClient gh;
    private final CodingAgent claude;
    private final Config config;
    private final boolean dryRun;

    ReviewWorkflow(GitHubClient gh, CodingAgent claude, Config config, boolean dryRun) {
        this.gh = gh;
        this.claude = claude;
        this.config = config;
        this.dryRun = dryRun;
    }

    /** Fixing rounds before we stop and ask a human. */
    private static final int MAX_ATTEMPTS = 3;

    WorkflowState.ReviewState advance(WorkflowState.ReviewEntry entry) {
        // A PR that landed or closed while we were mid-flow is finished, whatever
        // state we think it is in - check before doing any work on it.
        if (gh.isPRMerged(entry.ownerRepo, entry.prNumber)) {
            return WorkflowState.ReviewState.MERGED;
        }
        return switch (entry.state) {
            case NEW -> handleNew(entry);
            case REVIEW_POSTED, FIXING -> handleFixing(entry);
            case MONITORING_CI -> handleMonitoringCI(entry);
            case DONE -> WorkflowState.ReviewState.DONE;
            case MERGED -> WorkflowState.ReviewState.MERGED;
            case CLOSED -> WorkflowState.ReviewState.CLOSED;
        };
    }

    private WorkflowState.ReviewState handleNew(WorkflowState.ReviewEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int prNumber = entry.prNumber;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Reviewing: " + ownerRepo + "#" + prNumber + " — " + entry.title);
        System.out.println("=".repeat(60));

        JsonNode prDetails = gh.getPRDetails(ownerRepo, prNumber);
        if (prDetails == null) {
            System.out.println("  Could not fetch PR details, skipping this run.");
            return WorkflowState.ReviewState.NEW;
        }

        String title = prDetails.path("title").asText("");
        String body = prDetails.path("body").asText("");
        String author = prDetails.path("author").path("login").asText("");
        String headBranch = prDetails.path("headRefName").asText("");
        String defaultBranch = gh.getDefaultBranch(ownerRepo);

        System.out.println("  Cloning for review...");
        Path repoDir = null;
        try {
            repoDir = gh.cloneForReview(ownerRepo, prNumber, headBranch, author);
            if (repoDir == null) {
                System.out.println("  Clone failed, skipping this run.");
                return WorkflowState.ReviewState.NEW;
            }

            // Diff the LOCAL refs the clone actually has. headBranch is the
            // author's branch name, which exists on the fork and nowhere here -
            // the PR is fetched as pr-<n> - so diffing against it failed for
            // every review, silently, and the agent was handed an empty diff.
            String diff = gh.git(GitHubClient.Actor.USER, repoDir,
                    "diff", "upstream/" + defaultBranch + "...pr-" + prNumber);
            if (diff == null || diff.isBlank()) {
                System.out.println("  WARNING: empty diff for " + ownerRepo + "#" + prNumber
                        + " - review would be ungrounded, skipping to retry next run.");
                return WorkflowState.ReviewState.NEW;
            }
            if (diff.length() > 15000) diff = diff.substring(0, 15000) + "\n... (truncated)";

            String prompt = """
                    You are reviewing a pull request for the repository %s.

                    PR #%d: %s
                    Author: %s

                    PR description:
                    %s

                    Changes (diff against %s):
                    %s

                    Provide a thorough code review covering:
                    1. Correctness and completeness of the changes
                    2. Test coverage — are there tests? Are they adequate?
                    3. Documentation — are docs/javadoc updated where needed?
                    4. Security implications
                    5. Code quality and maintainability
                    6. Quarkus-specific patterns — check CLAUDE.md if present for project conventions

                    Format your review as a structured comment.
                    For each finding, indicate severity: [CRITICAL], [SUGGESTION], or [NIT].
                    End with a one-line overall assessment.

                    Output ONLY the review body text, nothing else.
                    """.formatted(ownerRepo, prNumber, title, author, body, defaultBranch, diff);

            System.out.println("  Running review...");
            String reviewBody = claude.run(prompt, repoDir, 15);

            if (reviewBody == null || reviewBody.isEmpty()) {
                System.out.println("  Review generation failed, will retry next run.");
                return WorkflowState.ReviewState.NEW;
            }

            if (dryRun) {
                System.out.println("  [DRY RUN] Would post review:\n" + truncate(reviewBody, 500));
            } else {
                gh.postPRReview(ownerRepo, prNumber, reviewBody);
                System.out.println("  Review posted on " + ownerRepo + "#" + prNumber);
            }

            entry.reviewBody = reviewBody;
            entry.headRepo = gh.getPRHeadRepo(ownerRepo, prNumber);
            entry.lastUpdated = Instant.now();

            if (!config.reviewAutoFix) {
                return WorkflowState.ReviewState.DONE;
            }
            // Nothing worth changing: go straight to watching CI so the PR can
            // still be merged on its own merits.
            if (!hasActionableFindings(reviewBody)) {
                System.out.println("  No actionable findings - watching CI.");
                return WorkflowState.ReviewState.MONITORING_CI;
            }
            // A fork's branch is not ours to write to. Say so on the PR rather
            // than silently reviewing and stopping.
            if (entry.headRepo == null || !entry.headRepo.equalsIgnoreCase(ownerRepo)) {
                System.out.println("  Head branch is on " + entry.headRepo + " - cannot push fixes.");
                gh.postComment(ownerRepo, prNumber,
                        "I can review this but not fix it: the head branch lives on `"
                                + entry.headRepo + "`, which I cannot push to. "
                                + "The findings above are for the author to apply.");
                return WorkflowState.ReviewState.DONE;
            }
            return WorkflowState.ReviewState.FIXING;

        } catch (Exception e) {
            System.err.println("  Error during review: " + e.getMessage());
            return WorkflowState.ReviewState.NEW;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.prNumber);
        }
    }

    /**
     * Whether the review raised anything we are configured to act on. Severities
     * are the tags the review prompt asks for; a review with only NITs (or none)
     * is not worth a commit.
     */
    private boolean hasActionableFindings(String reviewBody) {
        String upper = reviewBody.toUpperCase();
        return config.reviewFixSeverities.stream().anyMatch(sev -> upper.contains("[" + sev + "]"));
    }

    // --- FIXING: apply our own review to the PR branch -----------------------

    private WorkflowState.ReviewState handleFixing(WorkflowState.ReviewEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int prNumber = entry.prNumber;

        if (entry.attempts >= MAX_ATTEMPTS) {
            System.out.println("  Max fix attempts reached - handing back.");
            if (!dryRun) {
                gh.postComment(ownerRepo, prNumber,
                        "I have tried " + MAX_ATTEMPTS + " times and this still is not right. "
                                + "@" + config.githubUser + " please take a look.");
            }
            return WorkflowState.ReviewState.DONE;
        }
        entry.attempts++;

        System.out.println("  Applying review fixes (attempt " + entry.attempts + "/" + MAX_ATTEMPTS + ")...");
        Path repoDir = null;
        try {
            repoDir = gh.cloneForReview(ownerRepo, prNumber, entry.headBranch, entry.author);
            if (repoDir == null) {
                System.out.println("  Clone failed, will retry next run.");
                return WorkflowState.ReviewState.FIXING;
            }

            String severities = String.join(", ", config.reviewFixSeverities);
            String prompt = """
                    You are applying your own code review to the branch it was written about.
                    The working tree is the PR branch for %s#%d, already checked out.

                    Your review:
                    %s

                    Apply the findings tagged %s. Leave every other finding alone.

                    Rules:
                    - Change only what a finding calls for. This is not a rewrite.
                    - If a finding is wrong on closer reading, skip it and say so at the end.
                    - Update or add tests where the fix warrants it, and keep existing tests passing.
                    - Follow the conventions already in the file and in CLAUDE.md if present.
                    - Do NOT commit, push, or touch git. Only edit files.

                    Finish with a one-line summary per finding: fixed, or skipped and why.
                    """.formatted(ownerRepo, prNumber, entry.reviewBody, severities);

            String summary = claude.run(prompt, repoDir, 30);
            if (summary == null || summary.isEmpty()) {
                System.out.println("  Fix run produced nothing, will retry next run.");
                return WorkflowState.ReviewState.FIXING;
            }

            String status = gh.git(GitHubClient.Actor.BOT, repoDir, "status", "--porcelain");
            if (status == null || status.isBlank()) {
                System.out.println("  Agent changed nothing - treating findings as declined.");
                if (!dryRun) {
                    gh.postComment(ownerRepo, prNumber,
                            "On a second read I did not change anything:\n\n" + summary);
                }
                return WorkflowState.ReviewState.MONITORING_CI;
            }

            if (dryRun) {
                System.out.println("  [DRY RUN] Would commit and push:\n" + truncate(summary, 500));
                return WorkflowState.ReviewState.DONE;
            }

            gh.git(GitHubClient.Actor.BOT, repoDir, "add", "-A");
            gh.git(GitHubClient.Actor.BOT, repoDir, "commit", "-m",
                    "Apply review findings\n\n" + summary);

            if (!gh.pushReviewFixes(repoDir, entry.headRepo, entry.headBranch, prNumber)) {
                System.out.println("  Push failed - leaving the fixes unpushed.");
                gh.postComment(ownerRepo, prNumber,
                        "I fixed the findings above but could not push to `" + entry.headBranch
                                + "` on `" + entry.headRepo + "`. That usually means I lack write "
                                + "access. @" + config.githubUser);
                return WorkflowState.ReviewState.DONE;
            }

            gh.postComment(ownerRepo, prNumber, "Applied my own review:\n\n" + summary);
            System.out.println("  Fixes pushed to " + entry.headBranch);
            entry.lastUpdated = Instant.now();
            return WorkflowState.ReviewState.MONITORING_CI;

        } catch (Exception e) {
            System.err.println("  Error applying fixes: " + e.getMessage());
            return WorkflowState.ReviewState.FIXING;
        } finally {
            if (repoDir != null) gh.cleanupWorktree(entry.ownerRepo, entry.prNumber);
        }
    }

    // --- MONITORING_CI: merge once green ------------------------------------

    private WorkflowState.ReviewState handleMonitoringCI(WorkflowState.ReviewEntry entry) {
        String ownerRepo = entry.ownerRepo;
        int prNumber = entry.prNumber;

        System.out.println("  Checking CI on " + ownerRepo + "#" + prNumber + "...");
        GitHubClient.CIStatus status = gh.getCIStatus(ownerRepo, prNumber);

        switch (status) {
            case PENDING -> {
                System.out.println("  CI still running, waiting.");
                entry.noChecksTicks = 0;
                return WorkflowState.ReviewState.MONITORING_CI;
            }
            case NONE -> {
                // No checks at all. Wait a couple of ticks first, because
                // checks take a moment to register after a push and an empty
                // list looks identical either way. If they never appear, this
                // repo has no CI - and "merge once CI is green" cannot mean
                // "merge because nothing disagreed".
                entry.noChecksTicks++;
                if (entry.noChecksTicks < 3) {
                    System.out.println("  No checks yet (" + entry.noChecksTicks + "/3), waiting.");
                    return WorkflowState.ReviewState.MONITORING_CI;
                }
                System.out.println("  Repo has no CI - not merging on an absent gate.");
                if (!dryRun) {
                    gh.postComment(ownerRepo, prNumber,
                            "Review applied, but this repository has no CI, so there is nothing "
                                    + "for me to merge on. Merging needs a human. @" + config.githubUser);
                }
                return WorkflowState.ReviewState.DONE;
            }
            case FAIL -> {
                System.out.println("  CI failed - back to fixing.");
                entry.noChecksTicks = 0;
                entry.lastUpdated = Instant.now();
                return WorkflowState.ReviewState.FIXING;
            }
            case PASS -> {
                entry.noChecksTicks = 0;
                if (!config.reviewAutoMerge) {
                    System.out.println("  CI green; auto-merge off, leaving it.");
                    if (!dryRun) {
                        gh.postComment(ownerRepo, prNumber,
                                "Review applied and CI is green - ready to merge.");
                    }
                    return WorkflowState.ReviewState.DONE;
                }
                if (gh.hasMergeConflicts(ownerRepo, prNumber)) {
                    System.out.println("  Merge conflicts - handing back.");
                    if (!dryRun) {
                        gh.postComment(ownerRepo, prNumber,
                                "CI is green but this conflicts with the base branch. @"
                                        + config.githubUser);
                    }
                    return WorkflowState.ReviewState.DONE;
                }
                if (dryRun) {
                    System.out.println("  [DRY RUN] Would merge " + ownerRepo + "#" + prNumber);
                    return WorkflowState.ReviewState.DONE;
                }
                System.out.println("  CI green - merging.");
                if (gh.mergePR(ownerRepo, prNumber)) {
                    entry.lastUpdated = Instant.now();
                    return WorkflowState.ReviewState.MERGED;
                }
                gh.postComment(ownerRepo, prNumber,
                        "CI is green but I could not merge this. @" + config.githubUser);
                return WorkflowState.ReviewState.DONE;
            }
        }
        return WorkflowState.ReviewState.MONITORING_CI;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
