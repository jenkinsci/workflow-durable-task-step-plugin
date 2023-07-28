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
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.ChannelClosedException;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.util.DaemonThreadFactory;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;
import hudson.util.NamingThreadFactory;
import hudson.util.StreamTaskListener;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.tasks.filters.EnvVarsFilterableBuilder;
import jenkins.util.Timer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.Handler;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.log.OutputStreamTaskListener;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.jenkinsci.plugins.workflow.support.concurrent.WithThreadName;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Runs a durable task, such as a shell script, typically on an agent.
 * <p>“Durable” in this context means that Jenkins makes an attempt to keep the external process running
 * even if either the Jenkins controller or an agent JVM is restarted.
 * Process standard output is directed to a file near the workspace, rather than holding a file handle open.
 * Whenever a Remoting connection between the two can be reëstablished,
 * Jenkins again looks for any output sent since the last time it checked.
 * When the process exits, the status code is also written to a file and ultimately results in the step passing or failing.
 * <p>Tasks can also be run on the built-in node, which differs only in that there is no possibility of a network failure.
 */
public abstract class DurableTaskStep extends Step implements EnvVarsFilterableBuilder {

    private static final Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());

    private static final int MAX_LABEL_LENGTH = 100;

    private boolean returnStdout;
    private String encoding;
    private boolean returnStatus;
    private String label;

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

    @DataBoundSetter public void setLabel(String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    public String getLabel() {
        return label;
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        if (this.label != null) {
            context.get(FlowNode.class).addAction(new LabelAction(StringUtils.left(label, MAX_LABEL_LENGTH)));
        }
        return new Execution(context, this);
    }

    public abstract static class DurableTaskStepDescriptor extends StepDescriptor {

        @Restricted(DoNotUse.class)
        public FormValidation doCheckEncoding(@QueryParameter String encoding) {
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
        
        public FormValidation doCheckLabel(@QueryParameter String label) {
            if (label != null && label.length() > MAX_LABEL_LENGTH) {
                return FormValidation.error("Label size exceeds maximum of " + MAX_LABEL_LENGTH + " characters.");
            }
            return FormValidation.ok();
        }

        @Override public final Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }

    }

    /**
     * Something we will {@link Channel#export} to {@link HandlerImpl}.
     */
    interface ExecutionRemotable {
        /** @see Handler#exited */
        void exited(int code, byte[] output) throws Exception;
        /** A potentially recoverable problem was encountered in the watch task. */
        void problem(Exception x);
    }

    // TODO this and the other constants could be made customizable via system property
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "public & mutable only for tests")
    @Restricted(NoExternalUse.class)
    public static long WATCHING_RECURRENCE_PERIOD = /* 5m */300_000;

    /** If set to false, disables {@link Execution#watching} mode. */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "public & mutable only for tests")
    @Restricted(NoExternalUse.class)
    public static boolean USE_WATCHING = Boolean.getBoolean(DurableTaskStep.class.getName() + ".USE_WATCHING"); // JENKINS-52165: turn back on by default

    /** How many seconds to wait before interrupting remote calls and before forcing cleanup when the step is stopped */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "public & mutable for script console access")
    public static long REMOTE_TIMEOUT = Integer.parseInt(System.getProperty(DurableTaskStep.class.getName() + ".REMOTE_TIMEOUT", "20"));

    private static ScheduledThreadPoolExecutor threadPool;
    private static synchronized ScheduledExecutorService threadPool() {
        if (USE_WATCHING) {
            return Timer.get();
        }
        if (threadPool == null) {
            threadPool = new ScheduledThreadPoolExecutor(25, new NamingThreadFactory(new DaemonThreadFactory(), DurableTaskStep.class.getName()));
            threadPool.setKeepAliveTime(1, TimeUnit.MINUTES);
            threadPool.allowCoreThreadTimeOut(true);
        }
        return threadPool;
    }
    @Terminator public static synchronized void shutDownThreadPool() {
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }
    /**
     * Represents one task that is believed to still be running.
     * <p>This step has two modes, based on pulling or pushing log content from an agent.
     * In the default (push) mode, {@link Controller#watch} is used to ask the agent to begin streaming log content.
     * As new output is detected at regular intervals, it is streamed to the {@link TaskListener},
     * which in the default {@link StreamTaskListener} implementation sends chunks of text over Remoting.
     * When the process exits, {@link #exited} is called and the step execution also ends.
     * If Jenkins is restarted in the middle, {@link #onResume} starts a new watch task.
     * Every {@link #WATCHING_RECURRENCE_PERIOD}, the controller also checks to make sure the process still seems to be running using {@link Controller#exitStatus}.
     * If the agent connection is closed, {@link #ws} will be stale
     * ({@link FilePath#channel} will be {@link Channel#isClosingOrClosed})
     * and so {@link #getWorkspace} called from {@link #check} will call {@link #getWorkspaceProblem}
     * and we will attempt to get a fresh {@link #ws} as soon as possible, with a new watch.
     * (The persisted {@link #node} and {@link #remote} identify the workspace.)
     * If the process does not seem to be running after two consecutive checks,
     * yet no explicit process completion signal was sent,
     * {@link #awaitingAsynchExit} will make the step assume that the watch task is broken and the step should fail.
     * If sending output fails for any reason other than {@link ChannelClosedException},
     * {@link #problem} will attempt to record the issue but permit the step to proceed.
     * <p>In the older pull mode, available on request by {@link #USE_WATCHING} or when encountering a noncompliant {@link Controller} implementation,
     * the controller looks for process output ({@link Controller#writeLog}) and/or exit status in {@link #check} at variable intervals,
     * initially {@link #MIN_RECURRENCE_PERIOD} but slowing down by {@link #RECURRENCE_PERIOD_BACKOFF} up to {@link #MAX_RECURRENCE_PERIOD}.
     * Any new output will be noted in a change to the state of {@link #controller}, which gets saved to the step state in turn.
     * If there is any connection problem to the workspace (including controller restarts and Remoting disconnects),
     * {@link #ws} is nulled out and Jenkins waits until a fresh handle is available.
     */
    @SuppressFBWarnings(value="SE_TRANSIENT_FIELD_NOT_RESTORED", justification="recurrencePeriod is set in onResume, not deserialization")
    static final class Execution extends AbstractStepExecutionImpl implements Runnable, ExecutionRemotable {

        private static final long MIN_RECURRENCE_PERIOD = 250; // ¼s
        private static final long MAX_RECURRENCE_PERIOD = 15000; // 15s
        private static final float RECURRENCE_PERIOD_BACKOFF = 1.2f;

        private transient TaskListener newlineSafeTaskListener;
        /** Used only during {@link #start}. */
        private transient final DurableTaskStep step;
        /** Current “live” connection to the workspace, or null if we might be offline at the moment. */
        private transient FilePath ws;
        /**
         * How many ms we plan to sleep before running {@link #check} again.
         * Zero is used as a signal to break out of the loop.
         */
        private transient long recurrencePeriod;
        /** A handle for the fact that we plan to run {@link #check}. */
        private transient volatile ScheduledFuture<?> task;
        /** Defined only after {@link #stop} has been called. */
        private transient volatile ScheduledFuture<?> stopTask;
        /** Set if we have already notified the build log of a connectivity problem, which is done at most once per session. */
        private transient boolean printedCannotContactMessage;
        /** Serialized state of the controller. */
        private Controller controller;
        /** {@link Node#getNodeName} of {@link #ws}. */
        private String node;
        /** {@link FilePath#getRemote} of {@link #ws}. */
        private String remote;
        /** Whether the entire stdout of the process is to become the return value of the step. */
        private boolean returnStdout; // serialized default is false
        /** Whether the exit code of the process is to become the return value of the step. */
        private boolean returnStatus; // serialized default is false
        /** Whether we are using the newer push mode. */
        private boolean watching; // serialized default is false
        /** Only used when {@link #watching}, if after {@link #WATCHING_RECURRENCE_PERIOD} comes around twice {@link #exited} has yet to be called. */
        private transient boolean awaitingAsynchExit;
        /** The first throwable used to stop the task */
        private transient volatile Throwable causeOfStoppage;
        /** If nonzero, {@link System#nanoTime} when we first discovered that the node had been removed. */
        private transient long removedNodeDiscovered;

        Execution(StepContext context, DurableTaskStep step) {
            super(context);
            this.step = step;
        }

        @Override public boolean start() throws Exception {
            returnStdout = step.returnStdout;
            returnStatus = step.returnStatus;
            StepContext context = getContext();
            ws = context.get(FilePath.class);
            if (ws == null) {
                throw new AbortException("No workspace currently accessible");
            }
            node = FilePathUtils.getNodeName(ws);
            DurableTask durableTask = step.task();
            if (returnStdout) {
                durableTask.captureOutput();
            }
            TaskListener listener = listener();
            if (step.encoding != null) {
                durableTask.charset(Charset.forName(step.encoding));
            } else {
                durableTask.defaultCharset();
            }
            Launcher launcher = context.get(Launcher.class);
            launcher.prepareFilterRules(context.get(Run.class), step);
            LOGGER.log(Level.FINE, "launching task against {0} using {1}", new Object[] {ws.getChannel(), launcher});
            try {
                controller = durableTask.launch(context.get(EnvVars.class), ws, launcher, listener);
                LOGGER.log(Level.FINE, "launched task");
            } catch (Exception x) {
                LOGGER.log(Level.FINE, "failed to launch task", x);
                throw x;
            }
            this.remote = ws.getRemote();
            if (USE_WATCHING) {
                try {
                    controller.watch(ws, new HandlerImpl(this, ws, listener), listener);
                    watching = true;
                } catch (UnsupportedOperationException x) {
                    LOGGER.log(Level.WARNING, /* default exception message suffices */null, x);
                    // and we fall back to polling mode
                }
            }
            setupTimer(watching ? WATCHING_RECURRENCE_PERIOD : MIN_RECURRENCE_PERIOD);
            return false;
        }

        private @CheckForNull FilePath getWorkspace() throws IOException, InterruptedException {
            if (ws == null) {
                ws = FilePathUtils.find(node, remote);
                if (ws == null) {
                    // Part of JENKINS-49707: check whether an agent has been removed.
                    // (Note that a Computer may be missing because a Node is offline,
                    // and conversely after removing a Node its Computer may remain for a while.
                    // Therefore we only fail here if _both_ are absent.)
                    // ExecutorStepExecution.RemovedNodeListener will normally do this first, so this is a fallback.
                    Jenkins j = Jenkins.getInstanceOrNull();
                    if (ExecutorStepExecution.RemovedNodeCause.ENABLED && !node.isEmpty() && j != null && j.getNode(node) == null) {
                        if (removedNodeDiscovered == 0) {
                            LOGGER.fine(() -> "discovered that " + node + " has been removed");
                            removedNodeDiscovered = System.nanoTime();
                            return null;
                        } else if (System.nanoTime() - removedNodeDiscovered < TimeUnit.MILLISECONDS.toNanos(ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS)) {
                            LOGGER.fine(() -> "rediscovering that " + node + " has been removed");
                            return null;
                        } else {
                            LOGGER.fine(() -> "rediscovering that " + node + " has been removed and timeout has expired");
                            listener().getLogger().println(node + " has been removed for " + Util.getTimeSpanString(ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS) + ", assuming it is not coming back");
                            throw new FlowInterruptedException(Result.ABORTED, /* TODO false probably more appropriate */true, new ExecutorStepExecution.RemovedNodeCause());
                        }
                    }
                    removedNodeDiscovered = 0; // something else; reset
                    LOGGER.log(Level.FINE, "Jenkins is not running, no such node {0}, or it is offline", node);
                    return null;
                }
                removedNodeDiscovered = 0;
                if (watching) {
                    try {
                        controller.watch(ws, new HandlerImpl(this, ws, listener()), listener());
                        recurrencePeriod = WATCHING_RECURRENCE_PERIOD;
                    } catch (UnsupportedOperationException x) {
                        // Should not happen, since it worked in start() and a given Controller should not have *dropped* support.
                        getContext().onFailure(x);
                    } catch (Exception x) {
                        getWorkspaceProblem(x);
                        return null;
                    }
                }
            }
            boolean directory;
            try (Timeout timeout = Timeout.limit(REMOTE_TIMEOUT, TimeUnit.SECONDS)) {
                directory = ws.isDirectory();
            } catch (Exception x) {
                getWorkspaceProblem(x);
                return null;
            }
            if (!directory) {
                throw new AbortException("missing workspace " + remote + " on " + node);
            }
            LOGGER.log(Level.FINER, "{0} seems to be online so using {1}", new Object[] {node, remote});
            return ws;
        }

        private void getWorkspaceProblem(Exception x) {
            // RequestAbortedException, ChannelClosedException, EOFException, wrappers thereof; InterruptedException if it just takes too long.
            LOGGER.log(Level.FINE, node + " is evidently offline now", x);
            ws = null;
            recurrencePeriod = MIN_RECURRENCE_PERIOD;
            if (!printedCannotContactMessage) {
                listener().getLogger().println("Cannot contact " + node + ": " + x);
                printedCannotContactMessage = true;
            }
        }

        private synchronized @NonNull TaskListener listener() {
            if (newlineSafeTaskListener == null) {
                newlineSafeTaskListener = new NewlineSafeTaskListener(_listener());
            }
            return newlineSafeTaskListener;
        }

        private @NonNull TaskListener _listener() {
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

        /**
         * Interprets {@link OutputStream#close} as a signal to end a final newline if necessary.
         */
        private static final class NewlineSafeTaskListener extends OutputStreamTaskListener.Default {

            private static final long serialVersionUID = 1;

            private final TaskListener delegate;
            private transient OutputStream out;

            NewlineSafeTaskListener(TaskListener delegate) {
                this.delegate = delegate;
            }

            // Similar to DecoratedTaskListener:
            @Override public synchronized OutputStream getOutputStream() {
                if (out == null) {
                    out = new FilterOutputStream(OutputStreamTaskListener.getOutputStream(delegate)) {
                        boolean nl = true; // empty string does not need a newline
                        @Override public void write(int b) throws IOException {
                            super.write(b);
                            nl = b == '\n';
                        }
                        @Override public void write(@NonNull byte[] b, int off, int len) throws IOException {
                            super.write(b, off, len);
                            if (len > 0) {
                                nl = b[off + len - 1] == '\n';
                            }
                        }
                        @Override public void close() throws IOException {
                            LOGGER.log(Level.FINE, "calling close with nl={0}", nl);
                            if (!nl) {
                                super.write('\n');
                            }
                            flush(); // do *not* call base.close() here, unlike super.close()
                        }
                        @Override public String toString() {
                            return "NewlineSafeTaskListener.output[" + out + "]";
                        }
                    };
                }
                return out;
            }

        }

        private @NonNull Launcher launcher() throws IOException, InterruptedException {
            StepContext context = getContext();
            Launcher l = context.get(Launcher.class);
            if (l == null) {
                throw new IOException("JENKINS-37486: Launcher not present in " + context);
            }
            return l;
        }

        @Override public void stop(@NonNull final Throwable cause) throws Exception {
            causeOfStoppage = cause;
            FilePath workspace = getWorkspace();
            if (workspace != null) {
                listener().getLogger().println("Sending interrupt signal to process");
                LOGGER.log(Level.FINE, "stopping process", cause);
                stopTask = Timer.get().schedule(() -> {
                    stopTask = null;
                    if (recurrencePeriod > 0) {
                        recurrencePeriod = 0;
                        listener().getLogger().println("After " + REMOTE_TIMEOUT + "s process did not stop");
                        getContext().onFailure(cause);
                        try {
                            FilePath taskWorkspace = getWorkspace();
                            if (taskWorkspace != null) {
                                controller.cleanup(taskWorkspace);
                            }
                        } catch (IOException | InterruptedException x) {
                            Functions.printStackTrace(x, listener().getLogger());
                        }
                    }
                }, REMOTE_TIMEOUT, TimeUnit.SECONDS);
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
                    task = threadPool().schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
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
            } catch (IOException | InterruptedException x) {
                recurrencePeriod = 0;
                if (causeOfStoppage == null) { // do not doubly terminate
                    getContext().onFailure(x);
                }
                return;
            }
            if (workspace == null) {
                recurrencePeriod = Math.min((long) (recurrencePeriod * RECURRENCE_PERIOD_BACKOFF), MAX_RECURRENCE_PERIOD);
                return; // agent not yet ready, wait for another day
            }
            TaskListener listener = listener();
            try (Timeout timeout = Timeout.limit(REMOTE_TIMEOUT, TimeUnit.SECONDS)) {
                if (watching) {
                    Integer exitCode = controller.exitStatus(workspace, launcher(), listener);
                    if (exitCode == null) {
                        LOGGER.log(Level.FINE, "still running in {0} on {1}", new Object[] {remote, node});
                    } else if (recurrencePeriod == 0) {
                        LOGGER.fine(() -> "late check in " + remote + " on " + node + " ignored");
                    } else if (awaitingAsynchExit) {
                        recurrencePeriod = 0;
                        listener.getLogger().println("script apparently exited with code " + exitCode + " but asynchronous notification was lost");
                        handleExit(exitCode, () -> controller.getOutput(workspace, launcher()));
                    } else {
                        LOGGER.log(Level.FINE, "exited with {0} in {1} on {2}; expect asynchronous exit soon", new Object[] {exitCode, remote, node});
                        awaitingAsynchExit = true;
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
                            handleExit(exitCode, () -> controller.getOutput(workspace, launcher()));
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
            recurrencePeriod = 0;
            try {
                getContext().get(TaskListener.class);
            } catch (IOException | InterruptedException x) {
                // E.g., CpsStepContext.doGet complaining that getThreadSynchronously() == null.
                // If we cannot even print messages, there is no point proceeding.
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
            handleExit(exitCode, () -> output);
        }

        @FunctionalInterface
        private interface OutputSupplier {
            byte[] produce() throws IOException, InterruptedException;
        }
        private void handleExit(int exitCode, OutputSupplier output) throws IOException, InterruptedException {
            Throwable originalCause = causeOfStoppage;
            if ((returnStatus && originalCause == null) || exitCode == 0) {
                getContext().onSuccess(returnStatus ? exitCode : returnStdout ? new String(output.produce(), StandardCharsets.UTF_8) : null);
            } else {
                if (returnStdout) {
                    _listener().getLogger().write(output.produce()); // diagnostic
                }
                if (originalCause != null) {
                    // JENKINS-28822: Use the previous cause instead of throwing a new AbortException
                    _listener().getLogger().println("script returned exit code " + exitCode);
                    getContext().onFailure(originalCause);
                } else {
                    getContext().onFailure(new AbortException("script returned exit code " + exitCode));
                }
            }
            listener().getLogger().close();
        }

        // ditto
        @Override public void problem(Exception x) {
            Functions.printStackTrace(x, listener().getLogger());
            // note that if there is _also_ a problem in the controller-side logger, PrintStream will mask it
        }

        @Override public void onResume() {
            ws = null; // find it from scratch please
            setupTimer(MIN_RECURRENCE_PERIOD);
            // In watch mode, we will quickly enter the check.
            // Then in getWorkspace when ws == null we will start a watch and go back to sleep.
        }

        private void setupTimer(long initialRecurrencePeriod) {
            recurrencePeriod = initialRecurrencePeriod;
            task = threadPool().schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
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

        @Override public void output(@NonNull InputStream stream) throws Exception {
            PrintStream ps = listener.getLogger();
            OutputStream os = OutputStreamTaskListener.getOutputStream(listener);
            try {
                synchronized (ps) { // like PrintStream.write overloads do
                    IOUtils.copy(stream, os);
                }
                LOGGER.finest(() -> "print to " + os + " succeeded");
            } catch (ChannelClosedException x) {
                LOGGER.log(Level.FINE, null, x);
                // We are giving up on this watch. Wait for some call to getWorkspace to rewatch.
                throw x;
            } catch (Exception x) {
                LOGGER.log(Level.FINE, null, x);
                // Try to report it to the controller.
                try {
                    execution.problem(x);
                    // OK, printed to log on controller side, we may have lost some text but could continue.
                } catch (Exception x2) { // e.g., RemotingSystemException
                    LOGGER.log(Level.FINE, null, x2);
                    // No, channel seems to be broken, give up on this watch.
                    throw x;
                }
            }
        }

        @Override public void exited(int code, byte[] output) throws Exception {
            listener.getLogger().close();
            execution.exited(code, output);
        }

    }

    @Extension public static final class AgentReconnectionListener extends ComputerListener {

        @Override public void onOffline(Computer c, OfflineCause cause) {
            if (Jenkins.get().isTerminating()) {
                LOGGER.fine(() -> "Skipping check on " + c.getName() + " during shutdown");
                return;
            }
            check(c);
        }

        @Override public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (!FlowExecutionList.get().isResumptionComplete()) {
                LOGGER.fine(() -> "Skipping check on " + c.getName() + " before builds are ready");
                return;
            }
            check(c);
        }

        private void check(Computer c) {
            String name = c.getName();
            StepExecution.applyAll(Execution.class, exec -> {
                if (exec.watching && exec.node.equals(name)) {
                    LOGGER.fine(() -> "Online/offline event on " + name + ", checking current status of " + exec.remote + " soon");
                    threadPool().schedule(exec::check, 15, TimeUnit.SECONDS);
                }
                return null;
            });
        }

    }

}
