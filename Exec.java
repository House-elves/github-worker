import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs a subprocess and collects what it wrote.
 *
 * This class exists for the ORDER of two operations. A child process writing to
 * a pipe blocks as soon as that pipe is full, and the pipe is small - measured
 * at 8KB on the machine this was written on, not the 64KB the default suggests.
 * So waiting for the child to exit BEFORE reading its output deadlocks the
 * moment the output outgrows the buffer: the child cannot finish writing, the
 * parent will not start reading, and nothing breaks the tie but the timeout.
 *
 * Every call site here used to do exactly that, and the failure was invisible.
 * A pull request whose diff crossed the buffer could never be reviewed: the
 * `git diff` was killed at 300 seconds, the null it returned was
 * indistinguishable from a genuinely empty diff, and the review declined itself
 * as "ungrounded" - every hour, forever. Observed on
 * bin-space-website#8 (2026-08-16), a 10,647-byte diff stalled at 8,196 bytes
 * written, the process parked in anon_pipe_write. Small PRs went through
 * untouched, which is why it read as a repo quirk rather than a bug.
 *
 * Both streams are therefore drained on their own threads, started before the
 * wait, and a timeout is reported rather than swallowed.
 */
final class Exec {

    private Exec() {
    }

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    static Result run(ProcessBuilder pb, long timeout, TimeUnit unit) {
        return run(pb, timeout, unit, null);
    }

    /**
     * @param stdin written to the child and then closed, or null to leave the
     *              child's stdin alone. Written after the readers are running,
     *              so a prompt bigger than the pipe cannot deadlock either.
     */
    static Result run(ProcessBuilder pb, long timeout, TimeUnit unit, String stdin) {
        Process p = null;
        try {
            p = pb.start();

            Drain out = Drain.start(p.getInputStream());
            Drain err = Drain.start(p.getErrorStream());

            if (stdin != null) {
                try (var os = p.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            if (!p.waitFor(timeout, unit)) {
                p.destroyForcibly();
                return new Result(-1, out.text(), err.text(), true);
            }
            return new Result(p.exitValue(), out.text(), err.text(), false);
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Result(-1, "", message, false);
        }
    }

    /** Reads one stream to EOF on its own thread. */
    private static final class Drain implements Runnable {
        private final InputStream in;
        private final Thread thread;
        private volatile byte[] bytes = new byte[0];

        private Drain(InputStream in) {
            this.in = in;
            this.thread = new Thread(this, "exec-drain");
            this.thread.setDaemon(true);
        }

        static Drain start(InputStream in) {
            Drain d = new Drain(in);
            d.thread.start();
            return d;
        }

        @Override
        public void run() {
            try (in) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                // The process was killed mid-write. Whatever arrived is still
                // worth reporting, so keep it rather than failing the read.
            }
        }

        /** Waits briefly for the reader, so a killed child cannot hang the caller. */
        String text() {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }
}
