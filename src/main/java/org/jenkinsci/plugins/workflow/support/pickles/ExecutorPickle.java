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

package org.jenkinsci.plugins.workflow.support.pickles;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.SubTask;

import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepDynamicContext;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;

/**
 * Persists an {@link Executor} as the {@link hudson.model.Queue.Task} it was running.
 * That task can in turn have some way of producing a display name, a special {@link hudson.model.Queue.Executable} with a custom {@code executorCell.jelly}, and so on.
 * When rehydrated, the task is rescheduled, and when it starts executing the owning executor is produced.
 * Typically the {@link SubTask#getAssignedLabel} should be a {@link Node#getSelfLabel} so that the rehydrated executor is in fact on the same node.
 * @deprecated Normally now done via {@link ExecutorStepDynamicContext}.
 */
@Deprecated
public class ExecutorPickle extends Pickle {

    private static final Logger LOGGER = Logger.getLogger(ExecutorPickle.class.getName());

    private final Queue.Task task;

    private ExecutorPickle(Executor executor) {
        if (executor instanceof OneOffExecutor) {
            throw new IllegalArgumentException("OneOffExecutor not currently supported");
        }
        Queue.Executable exec = executor.getCurrentExecutable();
        if (exec == null) {
            throw new IllegalArgumentException("cannot save an Executor that is not running anything");
        }
        SubTask parent = exec.getParent();
        this.task = parent instanceof Queue.Task ? (Queue.Task) parent : parent.getOwnerTask();
        if (task instanceof Queue.TransientTask) {
            throw new IllegalArgumentException("cannot save a TransientTask");
        }
        LOGGER.log(Level.FINE, "saving {0}", task);
    }

    @Override public ListenableFuture<Executor> rehydrate(final FlowExecutionOwner owner) {
        return new TryRepeatedly<Executor>(1, 0) {
            long itemID;
            long endTimeNanos;

            @Override
            protected Executor tryResolve() throws Exception {
                if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
                    LOGGER.fine(() -> "not going to schedule " + task + " yet because Jenkins has not yet completed startup");
                    return null;
                }
                Queue.Item item;
                if (itemID == 0) { // Not scheduled yet
                    item = Queue.getInstance().schedule2(task, 0).getItem();
                    if (item == null) {
                        // TODO should also report when !ScheduleResult.created, since that is arguably an error
                        throw new IllegalStateException("queue refused " + task);
                    }
                    itemID = item.getId();
                    endTimeNanos = System.nanoTime() + ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS*1000000;
                    LOGGER.log(Level.FINE, "{0} scheduled {1}", new Object[] {ExecutorPickle.this, item});
                } else {
                    item = Queue.getInstance().getItem(itemID);
                    if (item == null) {
                        throw new IllegalStateException("queue lost item #" + itemID);
                    }
                    LOGGER.log(Level.FINE, "found {0}", item);
                }
                Future<Queue.Executable> future = item.getFuture().getStartCondition();

                if (!future.isDone()) {
                    Queue.Task task = item.task;
                    if (task instanceof ExecutorStepExecution.PlaceholderTask) {
                        ExecutorStepExecution.PlaceholderTask placeholder = (ExecutorStepExecution.PlaceholderTask)task;

                        // Non-null cookie means body has begun execution, i.e. we started using a node
                        // PlaceholderTask#getAssignedLabel is set to the Node name when execution starts
                        // Thus we're guaranteeing the execution began and the Node is now unknown.
                        // Theoretically it's safe to simply fail earlier when rehydrating any EphemeralNode... but we're being extra safe.
                        if (placeholder.getCookie() != null && Jenkins.get().getNode(placeholder.getAssignedLabel().getName()) == null ) {
                            if (System.nanoTime() > endTimeNanos) {
                                Queue.getInstance().cancel(item);
                                owner.getListener().getLogger().printf("Killed %s after waiting for %s because we assume unknown agent %s is never going to appear%n",
                                        item.task.getDisplayName(), Util.getTimeSpanString(ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS), placeholder.getAssignedLabel());
                                throw new FlowInterruptedException(Result.ABORTED, new ExecutorStepExecution.RemovedNodeCause());
                            }
                        }
                    }
                    LOGGER.log(Level.FINER, "{0} not yet started", new Object[]{item});
                    return null;
                }

                if (future.isCancelled()) {
                    throw new FlowInterruptedException(Result.ABORTED, true, new ExecutorStepExecution.QueueTaskCancelled());
                }

                Queue.Executable exec = future.get();
                Executor e = Executor.of(exec);
                if (e != null) {
                    LOGGER.log(Level.FINE, "from {0} found {1}", new Object[] {item, e});
                    return e;
                }

                // TODO this could happen as a race condition if the executable takes <1s to run; how could that be prevented?
                // Or can we schedule a placeholder Task whose Executable does nothing but return Executor.currentExecutor and then end?
                throw new IllegalStateException(exec + " was scheduled but no executor claimed it");
            }
            @Override protected FlowExecutionOwner getOwner() {
                return owner;
            }
            @Override protected void printWaitingMessage(TaskListener listener) {
                Queue.Item item = Queue.getInstance().getItem(itemID);
                String message = "Waiting to resume " + task.getFullDisplayName();
                if (item == null) { // ???
                    listener.getLogger().println(message);
                    return;
                }
                CauseOfBlockage causeOfBlockage = item.getCauseOfBlockage();
                if (causeOfBlockage != null) {
                    listener.getLogger().print(message + ": ");
                    causeOfBlockage.print(listener); // note that in case of Messages.Queue_Unknown for WaitingItem this is not very helpful
                } else {
                    listener.getLogger().println(message);
                }
            }
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                Queue.Item item = Queue.getInstance().getItem(itemID);
                if (item != null) {
                    if (Queue.getInstance().cancel(item)) {
                        LOGGER.log(Level.FINE, "canceled {0}", item);
                    } else {
                        LOGGER.log(Level.WARNING, "failed to cancel {0}", item);
                    }
                } else {
                    LOGGER.log(Level.FINE, "no such item {0} to cancel", itemID);
                }
                return super.cancel(mayInterruptIfRunning);
            }
            @Override public String toString() {
                Queue.Item item = Queue.getInstance().getItem(itemID);
                if (item != null) {
                    return "Trying to schedule " + task.getFullDisplayName() + "; blockage: " + item.getCauseOfBlockage();
                } else {
                    return "Trying to locate queue item #" + itemID;
                }
            }
        };
    }

    @Extension public static final class Factory extends SingleTypedPickleFactory<Executor> {
        @Override protected Pickle pickle(Executor object) {
            return new ExecutorPickle(object);
        }
    }

}
