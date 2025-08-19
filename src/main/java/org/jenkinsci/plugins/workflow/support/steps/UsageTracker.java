/*
 * The MIT License
 *
 * Copyright 2025 CloudBees, Inc.
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

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;

// TODO move to ContinuedTask once tested

/**
 * Serves essentially the same function as {@link ContinuedTask}, but more reliably:
 * the block on an agent in use does not rely on a {@link Queue.Item} having been scheduled.
 */
final class UsageTracker extends NodeProperty<Node> {

    private static final Logger LOGGER = Logger.getLogger(UsageTracker.class.getName());

    final List<ContinuedTask> tasks;

    private UsageTracker(List<ContinuedTask> tasks) {
        this.tasks = tasks;
    }

    // TODO cannot use NodeProperty.canTake because NodeProperty.setNode is never called

    @Extension public static final class QTD extends QueueTaskDispatcher {
        @Override public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            if (item.task instanceof ContinuedTask ct && ct.isContinued()) {
                LOGGER.finer(() -> item.task + " is a continued task, so we are not blocking it");
                return null;
            }
            var prop = node.getNodeProperty(UsageTracker.class);
            if (prop == null) {
                LOGGER.finer(() -> "not blocking " + item.task + " since " + node + " has no registrations");
                return null;
            }
            var c = node.toComputer();
            if (c == null) {
                LOGGER.finer(() -> "not blocking " + item + " since " + node + " has no computer");
                return null;
            }
            var executors = c.getExecutors();
            TASK: for (var task : prop.tasks) {
                for (var executor : executors) {
                    var executable = executor.getCurrentExecutable();
                    if (executable != null && executable.getParent().equals(task)) {
                        LOGGER.finer(() -> "not blocking " + item + " due to " + task + " since that is already running on " + c);
                        continue TASK;
                    }
                }
                if (task.getOwnerExecutable() instanceof Run<?, ?> build && !build.isInProgress()) {
                    LOGGER.finer(() -> "not blocking " + item + " due to " + task + " on " + c + " since " + build + " was already completed");
                    // TODO unregister stale entry
                    continue;
                }
                LOGGER.fine(() -> "blocking " + item.task + " in favor of " + task + " slated to run on " + c);
                return new HoldOnPlease(task);
            }
            LOGGER.finer(() -> "no reason to block " + item.task);
            return null;
        }
    }

    private static final class HoldOnPlease extends CauseOfBlockage {
        private final Queue.Task task;
        HoldOnPlease(Queue.Task task) {
            this.task = task;
        }
        @Override public String getShortDescription() {
            return task.getFullDisplayName() + " should be allowed to run first";
        }
    }

    public static void register(Node node, ContinuedTask task) throws IOException {
        LOGGER.fine(() -> "registering " + task + " on " + node);
        synchronized (node) {
            var prop = node.getNodeProperty(UsageTracker.class);
            List<ContinuedTask> tasks = prop != null ? new ArrayList<>(prop.tasks) : new ArrayList<>();
            tasks.add(task);
            node.getNodeProperties().replace(new UsageTracker(tasks));
        }
    }

    public static void unregister(Node node, ContinuedTask task) throws IOException {
        LOGGER.fine(() -> "unregistering " + task + " from " + node);
        synchronized (node) {
            var prop = node.getNodeProperty(UsageTracker.class);
            if (prop != null) {
                var tasks = new ArrayList<>(prop.tasks);
                tasks.remove(task);
                if (tasks.isEmpty()) {
                    node.getNodeProperties().remove(UsageTracker.class);
                } else {
                    node.getNodeProperties().replace(new UsageTracker(tasks));
                }
            }
        }
    }

    // TODO override reconfigure
    // TODO use NodeListener.onUpdated to transfer TrackingProperty so that io.jenkins.plugins.casc.core.JenkinsConfigurator will not delete info from permanent agents

}
