/*
 * The MIT License
 *
 * Copyright 2021 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.DefaultStepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Persistent representation for context of {@link ExecutorStepExecution}.
 * Supersedes {@link FilePathDynamicContext} (never mind {@link org.jenkinsci.plugins.workflow.support.pickles.FilePathPickle}),
 * {@link org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle},
 * {@link org.jenkinsci.plugins.workflow.support.pickles.ComputerPickle},
 * and {@link org.jenkinsci.plugins.workflow.support.pickles.WorkspaceListLeasePickle}.
 */
@Restricted(NoExternalUse.class)
public final class ExecutorStepDynamicContext implements Serializable {

    private static final Logger LOGGER = Logger.getLogger(ExecutorStepDynamicContext.class.getName());

    private static final long serialVersionUID = 1;

    final @NonNull ExecutorStepExecution.PlaceholderTask task;
    final @NonNull String node;
    private final @NonNull String path;
    /** Non-null after {@link #resume} if all goes well. */
    private transient @Nullable Executor executor;
    /** Non-null after {@link #resume} if all goes well. */
    private transient @Nullable WorkspaceList.Lease lease;

    ExecutorStepDynamicContext(ExecutorStepExecution.PlaceholderTask task, WorkspaceList.Lease lease, Executor executor) {
        this.task = task;
        this.node = FilePathUtils.getNodeName(lease.path);
        this.path = lease.path.getRemote();
        this.executor = executor;
        this.lease = lease;
    }

    void resume(StepContext context) throws Exception {
        if (executor != null) {
            throw new IllegalStateException("Already resumed");
        }
        while (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
            LOGGER.fine(() -> "waiting to schedule task for " + path + " on " + node + " until Jenkins completes startup");
            Thread.sleep(100);
        }
        Queue.Item item = Queue.getInstance().schedule2(task, 0).getItem();
        if (item == null) {
            // TODO should also report when !ScheduleResult.created, since that is arguably an error
            throw new IllegalStateException("queue refused " + task);
        }
        LOGGER.fine(() -> "scheduled " + item + " for " + path + " on " + node);
        TaskListener listener = context.get(TaskListener.class);
        if (!node.isEmpty()) { // unlikely to be any delay for built-in node anyway
            listener.getLogger().println("Waiting for reconnection of " + node + " before proceeding with build");
        }
        Queue.Executable exec;
        try {
            exec = item.getFuture().getStartCondition().get(ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException x) {
            listener.getLogger().println(node + " has been removed for " + Util.getTimeSpanString(ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS) + ", assuming it is not coming back");
            throw new FlowInterruptedException(Result.ABORTED, new ExecutorStepExecution.RemovedNodeCause());
        } catch (CancellationException x) {
            LOGGER.log(Level.FINE, "ceased to wait for " + node, x);
            return;
        }
        executor = Executor.of(exec);
        if (executor == null) {
            // TODO this could happen as a race condition if the executable takes <1s to run; how could that be prevented?
            // Or can we schedule a placeholder Task whose Executable does nothing but return Executor.currentExecutor and then end?
            throw new IOException(exec + " was scheduled but no executor claimed it");
        }
        Computer computer = executor.getOwner();
        VirtualChannel channel = computer.getChannel();
        if (channel == null) {
            throw new IOException(computer + " is offline");
        }
        FilePath fp = new FilePath(channel, path);
        // Since there is no equivalent to Lock.tryLock for WorkspaceList (.record would work but throws AssertionError and swaps the holder):
        WorkspaceList.Lease _lease = computer.getWorkspaceList().allocate(fp);
        if (_lease.path.equals(fp)) {
            lease = _lease;
        } else { // @2 or other variant, not what we expected to be able to lock without contention
            _lease.release();
            throw new IOException("JENKINS-37121: something already locked " + fp);
        }
        LOGGER.fine(() -> "fully restored for " + path + " on " + node);
    }

    private static abstract class Translator<T> extends DynamicContext.Typed<T> {

        @Override protected T get(DelegatedContext context) throws IOException, InterruptedException {
            ExecutorStepDynamicContext c = context.get(ExecutorStepDynamicContext.class);
            if (c == null || c.lease == null) {
                return null;
            }
            return get(c);
        }

        abstract T get(ExecutorStepDynamicContext c) throws IOException, InterruptedException;

    }

    @Extension public static final class FilePathTranslator extends Translator<FilePath> {

        @Override protected Class<FilePath> type() {
            return FilePath.class;
        }

        @Override FilePath get(ExecutorStepDynamicContext c) throws IOException {
            if (c.lease.path.toComputer() == null) {
                FilePath f = FilePathUtils.find(c.node, c.path);
                if (f != null) {
                    LOGGER.fine(() -> c.node + " disconnected and reconnected; getting a new FilePath on " + c.path + " with the new Channel");
                    return f;
                }
                String message = "Unable to create live FilePath for " + c.node;
                Computer comp = Jenkins.get().getComputer(c.node);
                if (comp != null) {
                    OfflineCause oc = comp.getOfflineCause();
                    if (oc != null) {
                        message += "; " + comp.getDisplayName() + " was marked offline: " + oc;
                    }
                }
                AgentOfflineException e = new AgentOfflineException(message);
                if (comp != null) {
                    for (Computer.TerminationRequest tr : comp.getTerminatedBy()) {
                        e.addSuppressed(tr);
                    }
                }
                throw e;
            }
            return c.lease.path;
        }

    }

    @Extension public static final class WorkspaceListLeaseTranslator extends Translator<WorkspaceList.Lease> {

        @Override protected Class<WorkspaceList.Lease> type() {
            return WorkspaceList.Lease.class;
        }

        @Override WorkspaceList.Lease get(ExecutorStepDynamicContext c) {
            // Do not do a liveness check as in FilePathTranslator.
            // We could not do anything about a stale .path even if we found out about it.
            return c.lease;
        }

    }

    @Extension public static final class ExecutorTranslator extends Translator<Executor> {

        @Override protected Class<Executor> type() {
            return Executor.class;
        }

        @Override Executor get(ExecutorStepDynamicContext c) {
            return c.executor;
        }

    }

    @Extension public static final class ComputerTranslator extends Translator<Computer> {

        @Override protected Class<Computer> type() {
            return Computer.class;
        }

        @Override Computer get(ExecutorStepDynamicContext c) {
            return c.executor.getOwner();
        }

    }

    /**
     * Need not use {@link Translator} since we can serve a {@link Node} even when offline.
     * Overrides default behavior in {@link DefaultStepContext} which would delegate to {@link ComputerTranslator}.
     */
    @Extension public static final class NodeTranslator extends DynamicContext.Typed<Node> {

        @Override protected Class<Node> type() {
            return Node.class;
        }

        @Override protected Node get(DelegatedContext context) throws IOException, InterruptedException {
            ExecutorStepDynamicContext c = context.get(ExecutorStepDynamicContext.class);
            if (c == null) {
                return null;
            }
            Jenkins j = Jenkins.get();
            return c.node.isEmpty() ? j : j.getNode(c.node);
        }

    }

}
