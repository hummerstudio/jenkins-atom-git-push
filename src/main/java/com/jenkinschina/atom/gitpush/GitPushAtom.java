package com.jenkinschina.atom.gitpush;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Pushes the workspace's commits to a remote and proves the remote moved.
 *
 * <p>The git-push plugin is a handful of checkboxes over {@code git push}. The part worth care is credentials: a
 * token in the URL ends up in {@code ps}, in the reflog of a temporary remote and in any error message git prints. So
 * the username goes in the URL, the password comes from {@code GIT_ASKPASS}, and the temporary askpass script is
 * removed afterwards. The push is then verified with {@code ls-remote}, because "git exited 0" and "the branch
 * moved" are not the same statement.
 */
public class GitPushAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String remote = context.getParam("remote", "origin").trim();
        String branch = context.getParam("branch", "").trim();
        String targetBranch = context.getParam("targetBranch", "").trim();
        boolean force = context.getBooleanParam("force", false);
        boolean setUpstream = context.getBooleanParam("setUpstream", true);
        boolean followTags = context.getBooleanParam("followTags", false);
        String username = context.getParam("username", "").trim();
        String token = context.getSecret("token");
        if (token == null || token.isBlank()) {
            token = context.getParam("token", "");
        }
        int timeoutSeconds = Math.max(10, Math.min(context.getIntParam("timeoutSeconds", 180), 1800));

        if (remote.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "remote is required (a name like origin, or a URL)");
        }
        String source = branch.isEmpty() ? currentBranch(workspace) : branch;
        if (source == null || source.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002,
                    "the workspace has no checked-out branch (detached HEAD?); set branch explicitly");
        }
        String destination = targetBranch.isEmpty() ? source : targetBranch;

        Path askpass = null;
        try {
            List<String> argv = new ArrayList<>();
            argv.add("git");
            String pushUrl = remote;
            if (!token.isBlank() && !username.isEmpty() && remote.startsWith("http")) {
                // username in the URL is harmless; the secret never is
                pushUrl = remote.replaceFirst("^(https?://)", "$1" + username + "@");
                askpass = Files.createTempFile("atom-git-", ".askpass");
                Files.writeString(askpass,
                        "#!/bin/sh\ncase \"$1\" in\n  *sername*) echo \"" + username + "\" ;;\n"
                                + "  *) echo \"" + token.replace("\"", "") + "\" ;;\nesac\n",
                        StandardCharsets.UTF_8);
                setExecutable(askpass);
                context.log("[git-push] credentials supplied through GIT_ASKPASS, not the command line");
            }
            argv.add("-C");
            argv.add(workspace.toString());
            argv.add("push");
            if (setUpstream) {
                argv.add("--set-upstream");
            }
            if (force) {
                argv.add("--force");
            }
            if (followTags) {
                argv.add("--follow-tags");
            }
            argv.add(pushUrl);
            argv.add(source + ":refs/heads/" + destination);

            Result pushed = run(argv, askpass, timeoutSeconds);
            if (pushed.exit != 0) {
                String text = pushed.output.toLowerCase(Locale.ROOT);
                // a rejected branch, a bad token and a protected branch are all decisions by the remote
                boolean userProblem = text.contains("authentication") || text.contains("permission denied")
                        || text.contains("could not read username") || text.contains("403")
                        || text.contains("non-fast-forward") || text.contains("rejected")
                        || text.contains("protected branch") || text.contains("pre-receive hook declined");
                ErrorType type = userProblem ? ErrorType.USER : ErrorType.ENVIRONMENT;
                String hint = "";
                if (text.contains("non-fast-forward") || text.contains("rejected")) {
                    hint = " (the remote branch has commits this checkout does not; rebase, or set force=true if "
                            + "that is really what you mean)";
                } else if (text.contains("authentication") || text.contains("permission denied")) {
                    hint = " (the token needs write access to " + remote + ")";
                }
                return AtomResult.error(type, 40003, "git push failed: " + reasonLine(pushed.output) + hint);
            }

            String localSha = revParse(workspace);
            String remoteSha = lsRemote(workspace, pushUrl, destination);
            boolean verified = !localSha.isEmpty() && localSha.equals(remoteSha);
            context.log("[git-push] " + source + " -> " + remote + " " + destination
                    + " (" + shortSha(localSha) + (verified ? ", verified on the remote)" : ", NOT verified)"));
            if (!verified) {
                return AtomResult.error(ErrorType.THIRD_PART, 40004,
                        "the push reported success but " + remote + " " + destination + " is at "
                                + shortSha(remoteSha) + ", not " + shortSha(localSha));
            }

            return AtomResult.success()
                    .data("branch", destination)
                    .data("sha", localSha)
                    .data("remote", remote)
                    .data("forced", force ? "true" : "false");
        } finally {
            if (askpass != null) {
                Files.deleteIfExists(askpass);
            }
        }
    }

    private static String currentBranch(Path workspace) throws Exception {
        Result result = run(List.of("git", "-C", workspace.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
                null, 30);
        String name = result.output.trim();
        return result.exit == 0 && !"HEAD".equals(name) ? name : null;
    }

    private static String revParse(Path workspace) throws Exception {
        Result result = run(List.of("git", "-C", workspace.toString(), "rev-parse", "HEAD"), null, 30);
        return result.exit == 0 ? result.output.trim() : "";
    }

    /** What the remote actually points at: the only statement that answers "did the push land". */
    private static String lsRemote(Path workspace, String url, String branch) throws Exception {
        Result result = run(List.of("git", "-C", workspace.toString(), "ls-remote", url, "refs/heads/" + branch),
                null, 60);
        if (result.exit != 0) {
            return "";
        }
        String line = result.output.trim();
        return line.isEmpty() ? "" : line.split("\\s+")[0];
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isEmpty()) {
            return "(missing)";
        }
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    /** The line that says why: git prints "To <url>" first and the actual reason further down. */
    private static String reasonLine(String value) {
        List<String> lines = value.lines().filter(line -> !line.isBlank()).toList();
        for (String line : lines) {
            String lowered = line.toLowerCase(Locale.ROOT);
            if (lowered.contains("rejected") || lowered.contains("fatal") || lowered.contains("error")
                    || lowered.contains("denied") || lowered.contains("hook")) {
                return line.trim();
            }
        }
        return lines.isEmpty() ? "(no output)" : lines.get(lines.size() - 1).trim();
    }

    private static void setExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException ignored) {
            file.toFile().setExecutable(true, true);
        }
    }

    private record Result(int exit, String output) {}

    private static Result run(List<String> argv, Path askpass, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(true);
        if (askpass != null) {
            builder.environment().put("GIT_ASKPASS", askpass.toString());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        } else {
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        }
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(124, "timed out after " + timeoutSeconds + "s");
        }
        return new Result(process.exitValue(), output);
    }
}
