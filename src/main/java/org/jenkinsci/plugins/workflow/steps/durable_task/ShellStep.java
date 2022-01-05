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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Map;

/**
 * Runs a Bourne shell script asynchronously on a build agent.
 */
public final class ShellStep extends DurableTaskStep {

    private final String script;

    @DataBoundConstructor public ShellStep(String script) {
        if (script==null)
            throw new IllegalArgumentException();
        this.script = script;
    }

    public String getScript() {
        return script;
    }

    @Override protected DurableTask task() {
        return new BourneShellScript(script);
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        String path = context.get(EnvVars.class).get("PATH");
        if (path != null && path.contains("$PATH")) {
            context.get(TaskListener.class).getLogger().println("Warning: JENKINS-41339 probably bogus PATH=" + path + "; perhaps you meant to use ‘PATH+EXTRA=/something/bin’?");
        }
        return super.start(context);
    }

    @Extension public static final class DescriptorImpl extends DurableTaskStepDescriptor {

        @NonNull
        @Override public String getDisplayName() {
            return "Shell Script";
        }

        @Override public String getFunctionName() {
            return "sh";
        }

        @Override public String argumentsToString(Map<String, Object> namedArgs) {
            Object script = namedArgs.get("script");
            return script instanceof String ? (String) script : null;
        }

    }

}
