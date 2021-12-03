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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.remoting.VirtualChannel;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
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
    final @CheckForNull String node;
    private final @NonNull String path;
    /** Non-null sometime after {@link #resume} if all goes well. */
    private transient @Nullable Executor executor;
    /** Non-null sometime after {@link #resume} if all goes well. */
    private transient @Nullable WorkspaceList.Lease lease;
    /** Non-null after {@link #resume}. */
    private transient @Nullable Future<Queue.Executable> future;

    ExecutorStepDynamicContext(ExecutorStepExecution.PlaceholderTask task, WorkspaceList.Lease lease, Executor executor) {
        this.task = task;
        this.node = FilePathUtils.getNodeNameOrNull(lease.path);
        this.path = lease.path.getRemote();
        this.executor = executor;
        this.lease = lease;
    }

    void resume() {
        if (executor != null) {
            throw new IllegalStateException("Already resumed");
        }
        Queue.Item item = Queue.getInstance().schedule2(task, 0).getItem();
        if (item == null) {
            // TODO should also report when !ScheduleResult.created, since that is arguably an error
            throw new IllegalStateException("queue refused " + task);
        }
        LOGGER.fine(() -> "scheduled " + item + " for " + path + " on " + node);
        future = item.getFuture().getStartCondition();
    }

    private static abstract class Translator<T> extends DynamicContext.Typed<T> {

        @SuppressWarnings("deprecation")
        @Override protected T get(DelegatedContext context) throws IOException, InterruptedException {
            ExecutorStepDynamicContext c = context.get(ExecutorStepDynamicContext.class);
            if (c == null) {
                return null;
            }
            if (c.executor == null || c.lease == null) {
                if (c.future == null) {
                    throw new IOException("Attempt to look up context from ExecutorStepExecution which has not been resumed");
                }
                LOGGER.fine(() -> "waiting for queue item to be scheduled for " + c.path + " on " + c.node);
                Queue.Executable exec;
                try {
                    // TODO block here, or just return null? DurableTaskStep.Execution.getWorkspace will tolerate nulls, but if there are any other resumable steps expecting a workspace, they could fail
                    exec = c.future.get(org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.TIMEOUT_WAITING_FOR_NODE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException | CancellationException x) {
                    // TODO pretty display of error, RemovedNodeCause, etc. from ExecutorPickle also from InterruptedException
                    throw new IOException(x);
                }
                c.executor = Executor.of(exec);
                if (c.executor == null) {
                    // TODO this could happen as a race condition if the executable takes <1s to run; how could that be prevented?
                    // Or can we schedule a placeholder Task whose Executable does nothing but return Executor.currentExecutor and then end?
                    throw new IOException(exec + " was scheduled but no executor claimed it");
                }
                Computer computer = c.executor.getOwner();
                VirtualChannel channel = computer.getChannel();
                if (channel == null) {
                    throw new IOException(computer + " is offline");
                }
                FilePath fp = new FilePath(channel, c.path);
                // Since there is no equivalent to Lock.tryLock for WorkspaceList (.record would work but throws AssertionError and swaps the holder):
                WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(fp);
                if (lease.path.equals(fp)) {
                    c.lease = lease;
                } else { // @2 or other variant, not what we expected to be able to lock without contention
                    lease.release();
                    throw new IOException("JENKINS-37121: something already locked " + fp);
                }
                LOGGER.fine(() -> "fully restored for " + c.path + " on " + c.node);
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
                IOException e = new IOException(message);
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

}
