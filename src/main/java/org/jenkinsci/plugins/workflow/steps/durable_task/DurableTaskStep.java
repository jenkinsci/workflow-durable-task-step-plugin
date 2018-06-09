/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Main;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.DaemonThreadFactory;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;
import hudson.util.NamingThreadFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.util.Timer;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.Handler;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.jenkinsci.plugins.workflow.support.concurrent.WithThreadName;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Runs an durable task on a slave, such as a shell script.
 */
public abstract class DurableTaskStep extends Step {

    private static final Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());

    private boolean returnStdout;
    private String encoding;
    private boolean returnStatus;

    protected abstract DurableTask task();

    public boolean isReturnStdout() {
        return returnStdout;
    }

    @DataBoundSetter public void setReturnStdout(boolean returnStdout) {
        this.returnStdout = returnStdout;
    }

    public String getEncoding() {
        return encoding;
    }

    @DataBoundSetter public void setEncoding(String encoding) {
        this.encoding = Util.fixEmpty(encoding);
    }

    public boolean isReturnStatus() {
        return returnStatus;
    }

    @DataBoundSetter public void setReturnStatus(boolean returnStatus) {
        this.returnStatus = returnStatus;
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, this);
    }

    public abstract static class DurableTaskStepDescriptor extends StepDescriptor {

        @Restricted(DoNotUse.class)
        public FormValidation doCheckEncoding(@QueryParameter boolean returnStdout, @QueryParameter String encoding) {
            if (encoding.isEmpty()) {
                return FormValidation.ok();
            }
            try {
                Charset.forName(encoding);
            } catch (Exception x) {
                return FormValidation.error(x, "Unrecognized encoding");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckReturnStatus(@QueryParameter boolean returnStdout, @QueryParameter boolean returnStatus) {
            if (returnStdout && returnStatus) {
                return FormValidation.error("You may not select both returnStdout and returnStatus.");
            }
            return FormValidation.ok();
        }

        @Override public final Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }

    }

    interface ExecutionRemotable {
        void exited(int code, byte[] output) throws Exception;
    }

    /**
     * Represents one task that is believed to still be running.
     */
    @SuppressFBWarnings(value="SE_TRANSIENT_FIELD_NOT_RESTORED", justification="recurrencePeriod is set in onResume, not deserialization")
    static final class Execution extends AbstractStepExecutionImpl implements Runnable, ExecutionRemotable {

        private static final long MIN_RECURRENCE_PERIOD = 250; // ¼s
        private static final long MAX_RECURRENCE_PERIOD = 15000; // 15s
        private static final float RECURRENCE_PERIOD_BACKOFF = 1.2f;
        private static final long WATCHING_RECURRENCE_PERIOD = Main.isUnitTest ? /* 5s */5_000: /* 5m */300_000;

        private static final ScheduledThreadPoolExecutor THREAD_POOL = new ScheduledThreadPoolExecutor(25, new NamingThreadFactory(new DaemonThreadFactory(), DurableTaskStep.class.getName()));
        static {
            THREAD_POOL.setKeepAliveTime(1, TimeUnit.MINUTES);
            THREAD_POOL.allowCoreThreadTimeOut(true);
        }

        private transient final DurableTaskStep step;
        private transient FilePath ws;
        private transient long recurrencePeriod;
        private transient volatile ScheduledFuture<?> task, stopTask;
        private transient boolean printedCannotContactMessage;
        private Controller controller;
        private String node;
        private String remote;
        private boolean returnStdout; // serialized default is false
        private boolean returnStatus; // serialized default is false
        private boolean watching;

        Execution(StepContext context, DurableTaskStep step) {
            super(context);
            this.step = step;
        }

        @Override public boolean start() throws Exception {
            returnStdout = step.returnStdout;
            returnStatus = step.returnStatus;
            StepContext context = getContext();
            ws = context.get(FilePath.class);
            node = FilePathUtils.getNodeName(ws);
            DurableTask durableTask = step.task();
            if (returnStdout) {
                durableTask.captureOutput();
            }
            TaskListener listener = context.get(TaskListener.class);
            if (step.encoding != null) {
                durableTask.charset(Charset.forName(step.encoding));
            } else {
                durableTask.defaultCharset();
            }
            controller = durableTask.launch(context.get(EnvVars.class), ws, context.get(Launcher.class), listener);
            this.remote = ws.getRemote();
            try {
                controller.watch(ws, new HandlerImpl(this, ws, listener), listener);
                watching = true;
            } catch (UnsupportedOperationException x) {
                LOGGER.log(Level.WARNING, null, x);
            }
            setupTimer(watching ? WATCHING_RECURRENCE_PERIOD : MIN_RECURRENCE_PERIOD);
            return false;
        }

        private @CheckForNull FilePath getWorkspace() throws AbortException {
            if (ws == null) {
                ws = FilePathUtils.find(node, remote);
                if (ws == null) {
                    LOGGER.log(Level.FINE, "Jenkins is not running, no such node {0}, or it is offline", node);
                    return null;
                }
                if (watching) {
                    try {
                        controller.watch(ws, new HandlerImpl(this, ws, listener()), listener());
                        recurrencePeriod = WATCHING_RECURRENCE_PERIOD;
                    } catch (UnsupportedOperationException x) {
                        getContext().onFailure(x);
                    } catch (Exception x) { // as below
                        LOGGER.log(Level.FINE, node + " is evidently offline now", x);
                        ws = null;
                        recurrencePeriod = MIN_RECURRENCE_PERIOD;
                        return null;
                    }
                }
            }
            boolean directory;
            try (Timeout timeout = Timeout.limit(10, TimeUnit.SECONDS)) {
                directory = ws.isDirectory();
            } catch (Exception x) {
                // RequestAbortedException, ChannelClosedException, EOFException, wrappers thereof; InterruptedException if it just takes too long.
                LOGGER.log(Level.FINE, node + " is evidently offline now", x);
                ws = null;
                recurrencePeriod = MIN_RECURRENCE_PERIOD;
                if (!printedCannotContactMessage) {
                    listener().getLogger().println("Cannot contact " + node + ": " + x);
                    printedCannotContactMessage = true;
                }
                return null;
            }
            if (!directory) {
                throw new AbortException("missing workspace " + remote + " on " + node);
            }
            LOGGER.log(Level.FINE, "{0} seems to be online", node);
            return ws;
        }

        private @Nonnull TaskListener listener() {
            TaskListener l;
            StepContext context = getContext();
            try {
                l = context.get(TaskListener.class);
                if (l != null) {
                    LOGGER.log(Level.FINEST, "JENKINS-34021: DurableTaskStep.Execution.listener present in {0}", context);
                } else {
                    LOGGER.log(Level.WARNING, "JENKINS-34021: TaskListener not available upon request in {0}", context);
                    l = new LogTaskListener(LOGGER, Level.FINE);
                }
            } catch (Exception x) {
                LOGGER.log(Level.FINE, "JENKINS-34021: could not get TaskListener in " + context, x);
                l = new LogTaskListener(LOGGER, Level.FINE);
                recurrencePeriod = 0;
            }
            return l;
        }

        private @Nonnull Launcher launcher() throws IOException, InterruptedException {
            StepContext context = getContext();
            Launcher l = context.get(Launcher.class);
            if (l == null) {
                throw new IOException("JENKINS-37486: Launcher not present in " + context);
            }
            return l;
        }

        @Override public void stop(final Throwable cause) throws Exception {
            FilePath workspace = getWorkspace();
            if (workspace != null) {
                listener().getLogger().println("Sending interrupt signal to process");
                LOGGER.log(Level.FINE, "stopping process", cause);
                stopTask = Timer.get().schedule(new Runnable() {
                    @Override public void run() {
                        stopTask = null;
                        if (recurrencePeriod > 0) {
                            recurrencePeriod = 0;
                            listener().getLogger().println("After 10s process did not stop");
                            getContext().onFailure(cause);
                            try {
                                FilePath workspace = getWorkspace();
                                if (workspace != null) {
                                    controller.cleanup(workspace);
                                }
                            } catch (IOException | InterruptedException x) {
                                Functions.printStackTrace(x, listener().getLogger());
                            }
                        }
                    }
                }, 10, TimeUnit.SECONDS);
                controller.stop(workspace, launcher());
            } else {
                listener().getLogger().println("Could not connect to " + node + " to send interrupt signal to process");
                recurrencePeriod = 0;
                super.stop(cause);
            }
        }

        @Override public String getStatus() {
            StringBuilder b = new StringBuilder();
            try (Timeout timeout = Timeout.limit(2, TimeUnit.SECONDS)) { // CpsThreadDump applies a 3s timeout anyway
                FilePath workspace = getWorkspace();
                if (workspace != null) {
                    b.append(controller.getDiagnostics(workspace, launcher()));
                } else {
                    b.append("waiting to reconnect to ").append(remote).append(" on ").append(node);
                }
            } catch (IOException | InterruptedException x) {
                b.append("failed to look up workspace ").append(remote).append(" on ").append(node).append(": ").append(x);
            }
            b.append("; recurrence period: ").append(recurrencePeriod).append("ms");
            ScheduledFuture<?> t = task;
            if (t != null) {
                b.append("; check task scheduled; cancelled? ").append(t.isCancelled()).append(" done? ").append(t.isDone());
            }
            t = stopTask;
            if (t != null) {
                b.append("; stop task scheduled; cancelled? ").append(t.isCancelled()).append(" done? ").append(t.isDone());
            }
            return b.toString();
        }

        /** Checks for progress or completion of the external task. */
        @Override public void run() {
            task = null;
            try (WithThreadName naming = new WithThreadName(": checking " + remote + " on " + node)) {
                check();
            } catch (Exception x) { // TODO use ErrorLoggingScheduledThreadPoolExecutor from core if it becomes public
                LOGGER.log(Level.WARNING, null, x);
            } finally {
                if (recurrencePeriod > 0) {
                    task = THREAD_POOL.schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
                }
            }
        }

        @SuppressFBWarnings(value="REC_CATCH_EXCEPTION", justification="silly rule")
        private void check() {
            if (recurrencePeriod == 0) { // from stop
                return;
            }
            final FilePath workspace;
            try {
                workspace = getWorkspace();
            } catch (AbortException x) {
                recurrencePeriod = 0;
                getContext().onFailure(x);
                return;
            }
            if (workspace == null) {
                recurrencePeriod = Math.min((long) (recurrencePeriod * RECURRENCE_PERIOD_BACKOFF), MAX_RECURRENCE_PERIOD);
                return; // slave not yet ready, wait for another day
            }
            TaskListener listener = listener();
            try (Timeout timeout = Timeout.limit(10, TimeUnit.SECONDS)) {
                if (watching) {
                    Integer exitCode = controller.exitStatus(workspace, launcher());
                    if (exitCode == null) {
                        LOGGER.log(Level.FINE, "still running in {0} on {1}", new Object[] {remote, node});
                    } else {
                        LOGGER.log(Level.FINE, "exited with {0} in {1} on {2}; expect asynchronous exit soon", new Object[] {exitCode, remote, node});
                        // TODO if we get here again and exited has still not been called, assume we lost the notification somehow and end the step
                    }
                } else { // legacy mode
                        if (controller.writeLog(workspace, listener.getLogger())) {
                            getContext().saveState();
                            recurrencePeriod = MIN_RECURRENCE_PERIOD; // got output, maybe we will get more soon
                        } else {
                            recurrencePeriod = Math.min((long) (recurrencePeriod * RECURRENCE_PERIOD_BACKOFF), MAX_RECURRENCE_PERIOD);
                        }
                        Integer exitCode = controller.exitStatus(workspace, launcher(), listener);
                        if (exitCode == null) {
                            LOGGER.log(Level.FINE, "still running in {0} on {1}", new Object[] {remote, node});
                        } else {
                            if (controller.writeLog(workspace, listener.getLogger())) {
                                LOGGER.log(Level.FINE, "last-minute output in {0} on {1}", new Object[] {remote, node});
                            }
                            if (returnStatus || exitCode == 0) {
                                getContext().onSuccess(returnStatus ? exitCode : returnStdout ? new String(controller.getOutput(workspace, launcher()), StandardCharsets.UTF_8) : null);
                            } else {
                                if (returnStdout) {
                                    listener.getLogger().write(controller.getOutput(workspace, launcher())); // diagnostic
                                }
                                getContext().onFailure(new AbortException("script returned exit code " + exitCode));
                            }
                            recurrencePeriod = 0;
                            controller.cleanup(workspace);
                        }
                }
            } catch (Exception x) {
                LOGGER.log(Level.FINE, "could not check " + workspace, x);
                ws = null;
                if (!printedCannotContactMessage) {
                    listener.getLogger().println("Cannot contact " + node + ": " + x);
                    printedCannotContactMessage = true;
                }
            }
        }

        // called remotely from HandlerImpl
        @Override public void exited(int exitCode, byte[] output) throws Exception {
            try {
                getContext().get(TaskListener.class);
            } catch (IOException | InterruptedException x) {
                LOGGER.log(Level.FINE, "asynchronous exit notification with code " + exitCode + " in " + remote + " on " + node + " ignored since step already seems dead", x);
                return;
            }
            LOGGER.log(Level.FINE, "asynchronous exit notification with code {0} in {1} on {2}", new Object[] {exitCode, remote, node});
            if (returnStdout && output == null) {
                getContext().onFailure(new IllegalStateException("expected output but got none"));
                return;
            } else if (!returnStdout && output != null) {
                getContext().onFailure(new IllegalStateException("did not expect output but got some"));
                return;
            }
            recurrencePeriod = 0;
            if (returnStatus || exitCode == 0) {
                getContext().onSuccess(returnStatus ? exitCode : returnStdout ? new String(output, StandardCharsets.UTF_8) : null);
            } else {
                if (returnStdout) {
                    listener().getLogger().write(output); // diagnostic
                }
                getContext().onFailure(new AbortException("script returned exit code " + exitCode));
            }
        }

        @Override public void onResume() {
            ws = null; // find it from scratch please, rewatching as needed
            setupTimer(MIN_RECURRENCE_PERIOD);
        }

        private void setupTimer(long initialRecurrencePeriod) {
            recurrencePeriod = initialRecurrencePeriod;
            task = THREAD_POOL.schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
        }

        private static final long serialVersionUID = 1L;

    }

    private static class HandlerImpl extends Handler {

        private static final long serialVersionUID = 1L;

        private final ExecutionRemotable execution;
        private final TaskListener listener;

        HandlerImpl(Execution execution, FilePath workspace, TaskListener listener) {
            this.execution = workspace.getChannel().export(ExecutionRemotable.class, execution);
            this.listener = listener;
        }

        @Override public void output(InputStream stream) throws Exception {
            IOUtils.copy(stream, listener.getLogger());
        }

        @Override public void exited(int code, byte[] output) throws Exception {
            execution.exited(code, output);
        }

    }

}
