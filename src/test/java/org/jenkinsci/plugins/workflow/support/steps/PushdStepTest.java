/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.FilePath;
import hudson.slaves.DumbSlave;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class PushdStepTest {

    @RegisterExtension
    private static final BuildWatcherExtension buildWatcher = new BuildWatcherExtension();
    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();
    private final LogRecorder logging = new LogRecorder().record(FilePathDynamicContext.class, Level.FINE);

    @Test
    void basics() throws Throwable {
        sessions.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node {dir('subdir') {echo(/now the pwd=${pwd()}/)}}", true));
            j.assertLogContains("now the pwd=" + j.jenkins.getWorkspaceFor(p).child("subdir"), j.buildAndAssertSuccess(p));
        });
    }

    @Test
    void restarting() throws Throwable {
        sessions.then(j -> {
            j.createSlave("remote", null, null);
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('remote') {dir('subdir') {semaphore 'restarting'; echo(/now the pwd=${pwd()}/)}}", true));
            WorkflowRun b = p.scheduleBuild2(0).getStartCondition().get();
            SemaphoreStep.waitForStart("restarting/1", b);
        });
        sessions.then(j -> {
            SemaphoreStep.success("restarting/1", null);
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getLastBuild();
            DumbSlave agent = (DumbSlave) j.jenkins.getNode("remote");
            assertNotNull(agent);
            j.waitOnline(agent);
            FilePath ws = agent.getWorkspaceFor(p);
            assertNotNull(ws);
            j.assertLogContains("now the pwd=" + ws.child("subdir"), j.assertBuildStatusSuccess(j.waitForCompletion(b)));
        });
    }

}
