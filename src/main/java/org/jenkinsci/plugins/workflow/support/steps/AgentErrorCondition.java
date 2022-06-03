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

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.slaves.WorkspaceList;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.flow.ErrorCondition;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.MissingContextVariableException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Determines whether a failure in {@link ExecutorStep} should be retried.
 */
@Restricted(Beta.class)
public final class AgentErrorCondition extends ErrorCondition {

    @DataBoundConstructor public AgentErrorCondition() {}

    @Override public boolean test(Throwable t, StepContext context) throws IOException, InterruptedException {
        if (t instanceof FlowInterruptedException && ((FlowInterruptedException) t).getCauses().stream().anyMatch(ExecutorStepExecution.RemovedNodeCause.class::isInstance)) {
            return true;
        }
        if (isClosedChannel(t)) {
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
        return false;
    }

    private static boolean isClosedChannel(Throwable t) {
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

    @Symbol("agent")
    @Extension public static final class DescriptorImpl extends ErrorConditionDescriptor {

        @Override public String getDisplayName() {
            return "Agent errors";
        }

    }

}
