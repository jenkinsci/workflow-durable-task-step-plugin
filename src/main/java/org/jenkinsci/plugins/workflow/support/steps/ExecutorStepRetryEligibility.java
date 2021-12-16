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

import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import java.io.EOFException;
import java.nio.channels.ClosedChannelException;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.MissingContextVariableException;
import org.jenkinsci.plugins.workflow.steps.SynchronousResumeNotSupportedException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Determines whether a failure in {@link ExecutorStep} should be retried.
 */
@Restricted(Beta.class)
public interface ExecutorStepRetryEligibility extends ExtensionPoint {

    boolean shouldRetry(Throwable t, String node, String label, TaskListener listener);

    // TODO take TaskListener so as to print messages in selective cases?
    static boolean isGenerallyEligible(Throwable t) {
        if (t instanceof FlowInterruptedException && ((FlowInterruptedException) t).getCauses().stream().anyMatch(ExecutorStepExecution.RemovedNodeCause.class::isInstance)) {
            return true;
        }
        class Holder { // TODO Java 11+ use private static method
            boolean isClosedChannel(Throwable t) {
                if (t instanceof ClosedChannelException) {
                    return true;
                } else if (t instanceof EOFException) {
                    return true;
                } else if (t == null) {
                    return false;
                } else {
                    return isClosedChannel(t.getCause());
                }
            }
        }
        if (new Holder().isClosedChannel(t)) {
            return true;
        }
        if (t instanceof MissingContextVariableException) {
            Class<?> type = ((MissingContextVariableException) t).getType();
            // See ExecutorStepDynamicContext for four explicitly advertised types, & DefaultStepContext for two implicitly derived ones.
            // ExecutorStepExecution also offers EnvVars in context, but this is available to all builds anyway.
            if (type == FilePath.class || type == WorkspaceList.Lease.class || type == Computer.class || type == Executor.class || type == Node.class || type == Launcher.class) {
                return true;
            }
        }
        if (t instanceof SynchronousResumeNotSupportedException) {
            return true;
        }
        return false;
    }


}
