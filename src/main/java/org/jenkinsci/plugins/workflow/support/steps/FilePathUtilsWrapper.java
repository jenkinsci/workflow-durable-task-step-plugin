package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.FilePathUtils;

final class FilePathUtilsWrapper {

    /** Zero, the default, disables the wait. */
    static Duration RECONNECT_WAIT = Duration.ofSeconds(SystemProperties.getLong(FilePathDynamicContext.class.getName() + ".reconnectWaitSeconds", 0L));

    /** Like {@link FilePathUtils#find}, but waits up to {@link #RECONNECT_WAIT} for an agent that may be reconnecting, blocking the calling thread. */
    static @CheckForNull FilePath find(String nodeName, String path, @CheckForNull TaskListener listener) throws IOException {
        FilePath f = FilePathUtils.find(nodeName, path);
        if (f != null || RECONNECT_WAIT.isZero() || RECONNECT_WAIT.isNegative()) {
            return f;
        }
        long deadline = System.nanoTime() + RECONNECT_WAIT.toNanos();
        Computer c = Jenkins.get().getComputer(nodeName);
        if (mightReconnect(c) && listener != null) {
            listener.getLogger().println("Waiting up to " + Util.getTimeSpanString(RECONNECT_WAIT.toMillis()) + " for " + c.getDisplayName() + " to reconnect before giving up on " + path);
        }
        while (mightReconnect(c) && System.nanoTime() < deadline) {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            f = FilePathUtils.find(nodeName, path);
            if (f != null) {
                return f;
            }
            c = Jenkins.get().getComputer(nodeName);
        }
        return null;
    }

    private static boolean mightReconnect(@CheckForNull Computer c) {
        return c != null && c.getTerminatedBy().isEmpty() && !c.isTemporarilyOffline();
    }
}
