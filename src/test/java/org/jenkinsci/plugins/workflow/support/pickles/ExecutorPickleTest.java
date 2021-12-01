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

import hudson.Functions;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.InterruptedBuildAction;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.LoggerRule;

public class ExecutorPickleTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Rule public LoggerRule logging = new LoggerRule();

    @Ignore("TODO needs to be adapted to new behavior, probably using sh")
    @Test public void canceledQueueItem() throws Throwable {
        sessions.then(j -> {
                DumbSlave s = j.createSlave(Label.get("remote"));
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node('remote') {semaphore 'wait'}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                j.jenkins.removeNode(s);
        });
        sessions.then(j -> {
                SemaphoreStep.success("wait/1", null);
                WorkflowRun b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
                // first prints on 2.35-: hudson.model.Messages.Queue_WaitingForNextAvailableExecutor(); 2.36+: hudson.model.Messages.Node_LabelMissing("Jenkins", "slave0")
                // TODO this now does not get printed; instead build just completes:
                j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getFullDisplayName())), b);
                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                Queue.getInstance().cancel(items[0]);
                j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
                InterruptedBuildAction iba = b.getAction(InterruptedBuildAction.class);
                assertNotNull(iba);
                assertEquals(Collections.singleton(ExecutorStepExecution.QueueTaskCancelled.class), iba.getCauses().stream().map(Object::getClass).collect(Collectors.toSet()));
        });
    }

    /**
     * Test that a build will not spin forever trying to resume if it was using an
     *  node that disappeared and will never reappear... but still waits a little bit to find out.
     *
     *  I.E. cases where the {@link RetentionStrategy} is {@link RetentionStrategy#NOOP}.
     */
    @Issue("JENKINS-36013")
    @Test public void normalNodeDisappearance() throws Throwable {
        Assume.assumeFalse("TODO figure out corresponding batch script", Functions.isWindows());
        logging.record(DurableTaskStep.class, Level.FINE).record(ExecutorStepExecution.class, Level.FINE);
        sessions.then(j -> {
            // Start up a build that needs executor and then reboot and take the node offline
            // Starting job first ensures we don't immediately fail if Node comes from a Cloud
            //  and takes a min to provision
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('ghost') {sh 'sleep infinity'}", true));

            DumbSlave s = j.createSlave(Label.get("ghost"));
            j.waitForMessage("+ sleep infinity", p.scheduleBuild2(0).waitForStart());
            j.jenkins.removeNode(s);
        });

        sessions.then(j -> {
            // Start up a build and then reboot and take the node offline
            assertEquals(0, j.jenkins.getLabel("ghost").getNodes().size()); // Make sure test impl is correctly deleted
            assertNull(j.jenkins.getNode("ghost")); // Make sure test impl is correctly deleted
            WorkflowRun run = j.jenkins.getItemByFullName("p", WorkflowJob.class).getLastBuild();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
            assertThat(j.jenkins.getQueue().getItems(), emptyArray());
            InterruptedBuildAction iba = run.getAction(InterruptedBuildAction.class);
            assertNotNull(iba);
            assertEquals(Collections.singleton(ExecutorStepExecution.RemovedNodeCause.class), iba.getCauses().stream().map(Object::getClass).collect(Collectors.toSet()));
        });
    }

}
