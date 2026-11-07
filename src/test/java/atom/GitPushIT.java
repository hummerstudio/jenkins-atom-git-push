package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The git-push atom against real bare repositories: the push either moved the branch on the remote or it did not, and
 * that is what the old {@code tools/smoke.sh} checked by reading the remote's own refs.
 *
 * <p>The workspace is a repository the test builds, handed to the atom through {@code atom_workspace} — the same
 * parameter the runner fills in.
 */
class GitPushIT {

    private Atom atom;
    private Path scratch;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        scratch = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "gitpush-");
    }

    @Test
    @DisplayName("a plain push lands and the remote sha is the local one")
    void plainPushLands() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString(), "branch", "main"))
                .env("GIT_AUTHOR_NAME", "smoke").env("GIT_AUTHOR_EMAIL", "smoke@local")
                .env("GIT_COMMITTER_NAME", "smoke").env("GIT_COMMITTER_EMAIL", "smoke@local")
                .run();

        run.assertOk();
        assertEquals("main", run.field("branch"));
        assertEquals(head(ws), run.field("sha"), "sha output");
        assertEquals(head(ws), remoteSha(remote, "main"), "the branch must move on the remote");
        assertTrue(run.console().contains("verified on the remote"),
                "the atom should say it verified the remote:\n" + run.console());
    }

    @Test
    @DisplayName("a second commit pushes again, and the default branch is the checked-out one")
    void secondCommitPushesAgain() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");

        AtomResult first = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString()))
                .run();
        first.assertOk();

        Files.writeString(ws.resolve("app.txt"), "two\n", StandardCharsets.UTF_8);
        commit(ws, "second commit");

        AtomResult second = Atom.of()
                .input(json("atom_workspace", ws.toString(), "remote", remote.toString()))
                .run();

        second.assertOk();
        assertEquals(head(ws), remoteSha(remote, "main"), "the second push must move main");
    }

    @Test
    @DisplayName("targetBranch pushes the current branch to a different name on the remote")
    void differentDestinationBranch() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString(),
                        "branch", "main", "targetBranch", "release/1.0"))
                .run();

        run.assertOk();
        assertEquals("release/1.0", run.field("branch"));
        assertEquals(head(ws), remoteSha(remote, "release/1.0"), "the new branch must exist on the remote");
    }

    @Test
    @DisplayName("a non-fast-forward is refused as a user error, with the hint that says what to do")
    void nonFastForwardRefused() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");
        Atom.of().input(json("atom_workspace", ws.toString(), "remote", remote.toString())).run().assertOk();

        Path other = scratch.resolve("other");
        git(scratch, "clone", "-q", remote.toString(), other.toString());
        Files.writeString(other.resolve("app.txt"), "theirs\n", StandardCharsets.UTF_8);
        commit(other, "someone else committed");
        git(other, "push", "-q", "origin", "main");

        Files.writeString(ws.resolve("app.txt"), "mine\n", StandardCharsets.UTF_8);
        commit(ws, "my divergent commit");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString(), "branch", "main"))
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("rebase, or set force=true"),
                "the hint is missing: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("\"errorType\":1"), "a rejected push is a USER error");
    }

    @Test
    @DisplayName("force makes the same push go through and says so")
    void forcePushes() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");
        Atom.of().input(json("atom_workspace", ws.toString(), "remote", remote.toString())).run().assertOk();

        Path other = scratch.resolve("other");
        git(scratch, "clone", "-q", remote.toString(), other.toString());
        Files.writeString(other.resolve("app.txt"), "theirs\n", StandardCharsets.UTF_8);
        commit(other, "someone else committed");
        git(other, "push", "-q", "origin", "main");

        Files.writeString(ws.resolve("app.txt"), "mine\n", StandardCharsets.UTF_8);
        commit(ws, "my divergent commit");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString(),
                        "branch", "main", "force", "true"))
                .run();

        run.assertOk();
        assertEquals(head(ws), remoteSha(remote, "main"), "force should have moved the branch");
        assertEquals("true", run.field("forced"), "forced output: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a remote that does not exist fails the step")
    void missingRemoteFails() throws Exception {
        Path ws = repository("workspace");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(),
                        "remote", ws.resolve("no-such-remote.git").toString(), "branch", "main"))
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git push failed"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("without a token the atom never claims it used askpass")
    void noAskpassWithoutCredentials() throws Exception {
        Path ws = repository("workspace");
        Path remote = bareRemote("app.git");

        AtomResult run = atom.input(json("atom_workspace", ws.toString(), "remote", remote.toString(), "branch", "main"))
                .run();

        run.assertOk();
        assertFalse(run.console().contains("GIT_ASKPASS"),
                "the log must not claim askpass without credentials:\n" + run.console());
    }

    @Test
    @DisplayName("with a token the credentials travel through askpass and the token is never printed")
    void tokenTravelsThroughAskpass() throws Exception {
        Path ws = repository("workspace");

        AtomResult run = atom
                .file(".secrets.json", "{\"token\": \"s3cr3t-token\"}")
                .input(json("atom_workspace", ws.toString(), "remote", "https://example.invalid/app.git",
                        "branch", "main", "username", "ci"))
                .run();

        // the host does not resolve; what matters is how the credentials were handed over
        assertNotEquals(0, run.exit());
        assertTrue(run.console().contains("GIT_ASKPASS, not the command line"),
                "the atom should say how it supplied credentials:\n" + run.console());
        assertFalse(run.console().contains("s3cr3t-token"), "the token must not be printed");
    }

    // ------------------------------------------------------------------ fixtures

    private Path repository(String name) throws Exception {
        Path ws = Files.createDirectories(scratch.resolve(name));
        git(ws, "init", "-q", "-b", "main");
        Files.writeString(ws.resolve("app.txt"), "one\n", StandardCharsets.UTF_8);
        git(ws, "add", "-A");
        commit(ws, "first commit");
        return ws;
    }

    private Path bareRemote(String name) throws Exception {
        Path remote = scratch.resolve(name);
        git(scratch, "init", "-q", "--bare", remote.toString());
        return remote;
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "-c", "commit.gpgsign=false", "commit", "-q", "-m", message);
    }

    private static String head(Path repo) throws Exception {
        return git(repo, "rev-parse", "HEAD").trim();
    }

    private static String remoteSha(Path remote, String branch) throws Exception {
        Result result = run(null, "git", "--git-dir=" + remote, "rev-parse", "refs/heads/" + branch);
        return result.exit == 0 ? result.output.trim() : "";
    }

    private static String git(Path dir, String... args) throws Exception {
        Result result = run(dir, concat(List.of("git"), List.of(args)));
        if (result.exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed in " + dir + ":\n" + result.output);
        }
        return result.output;
    }

    private static Result run(Path dir, String... command) throws Exception {
        return run(dir, List.of(command));
    }

    private static Result run(Path dir, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_AUTHOR_NAME", "smoke");
        builder.environment().put("GIT_AUTHOR_EMAIL", "smoke@local");
        builder.environment().put("GIT_COMMITTER_NAME", "smoke");
        builder.environment().put("GIT_COMMITTER_EMAIL", "smoke@local");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return new Result(process.exitValue(), output);
    }

    private record Result(int exit, String output) {}

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String json(String... pairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(pairs[i]).append("\": \"")
                    .append(pairs[i + 1].replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return out.append('}').toString();
    }
}
