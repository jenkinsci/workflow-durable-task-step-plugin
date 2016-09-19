/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

import hudson.model.Label;
import hudson.model.Queue;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class ExecutorPickleTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule r = new RestartableJenkinsRule();

    @Test public void canceledQueueItem() throws Exception {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = r.j.createSlave(Label.get("remote"));
                WorkflowJob p = r.j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node('remote') {semaphore 'wait'}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                r.j.jenkins.removeNode(s);
            }
        });
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                SemaphoreStep.success("wait/1", null);
                WorkflowRun b = r.j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
                r.j.waitForMessage(hudson.model.Messages.Queue_WaitingForNextAvailableExecutor(), b);
                r.j.assertLogContains(Messages.ExecutorPickle_waiting_to_resume(Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getFullDisplayName())), b);
                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                Queue.getInstance().cancel(items[0]);
                r.j.waitForCompletion(b);
                // Do not bother with assertBuildStatus; we do not really care whether it is ABORTED or FAILURE
            }
        });
    }

}
