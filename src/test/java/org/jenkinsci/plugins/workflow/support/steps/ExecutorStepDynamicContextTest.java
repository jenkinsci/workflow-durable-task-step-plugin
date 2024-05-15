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

package org.jenkinsci.plugins.workflow.support.steps;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import jenkins.model.InterruptedBuildAction;
import org.jenkinci.plugins.mock_slave.MockCloud;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.LoggerRule;

public class ExecutorStepDynamicContextTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();
    @Rule public LoggerRule logging = new LoggerRule();

    private void commonSetup() {
        logging.recordPackage(ExecutorStepExecution.class, Level.FINE).record(FlowExecutionList.class, Level.FINE);
    }

    @Test public void canceledQueueItem() throws Throwable {
        sessions.then(j -> {
            DumbSlave s = j.createSlave(Label.get("remote"));
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('remote') {semaphore 'wait'; isUnix()}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            j.jenkins.removeNode(s);
        });
        sessions.then(j -> {
            SemaphoreStep.success("wait/1", null);
            WorkflowRun b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            await().until(() -> j.jenkins.getQueue().getItems(), emptyArray());
            Queue.Item[] items = Queue.getInstance().getItems();
            assertEquals(1, items.length);
            Queue.getInstance().cancel(items[0]);
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
            InterruptedBuildAction iba = b.getAction(InterruptedBuildAction.class);
            assertNotNull(iba);
            assertThat(iba.getCauses(), contains(anyOf(
                isA(ExecutorStepExecution.QueueTaskCancelled.class), // normal
                isA(ExecutorStepExecution.RemovedNodeTimeoutCause.class)))); // observed on occasion
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
        commonSetup();
        sessions.then(j -> {
            // Start up a build that needs executor and then reboot and take the node offline
            // Starting job first ensures we don't immediately fail if Node comes from a Cloud
            //  and takes a min to provision
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('ghost') {if (isUnix()) {sh 'sleep infinity'} else {bat 'echo + sleep infinity && ping -n 999999 localhost'}}", true));

            DumbSlave s = j.createSlave(Label.get("ghost"));
            j.waitForMessage("+ sleep infinity", p.scheduleBuild2(0).waitForStart());
            j.jenkins.removeNode(s);
        });

        sessions.then(j -> {
            // Start up a build and then reboot and take the node offline
            assertEquals(0, j.jenkins.getLabel("ghost").getNodes().size()); // Make sure test impl is correctly deleted
            WorkflowRun run = j.jenkins.getItemByFullName("p", WorkflowJob.class).getLastBuild();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
            j.assertLogContains("slave0 has been removed for ", run);
            assertThat(j.jenkins.getQueue().getItems(), emptyArray());
            InterruptedBuildAction iba = run.getAction(InterruptedBuildAction.class);
            assertNotNull(iba);
            assertThat(iba.getCauses(), contains(isA(ExecutorStepExecution.RemovedNodeTimeoutCause.class)));
        });
    }

    @Issue("JENKINS-36013")
    @Test public void parallelNodeDisappearance() throws Throwable {
        commonSetup();
        sessions.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("def bs = [:]; for (int _i = 0; _i < 5; _i++) {def i = _i; bs[/b$i/] = {node('remote') {semaphore(/s$i/)}}}; parallel bs", true));
            List<DumbSlave> agents = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                agents.add(j.createSlave(Label.get("remote")));
            }
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            for (int i = 0; i < 5; i++) {
                SemaphoreStep.waitForStart("s" + i + "/1", b);
            }
            for (DumbSlave agent : agents) {
                j.jenkins.removeNode(agent);
            }
        });
        sessions.then(j -> {
            logging.record(Queue.class, Level.INFO).capture(100);
            for (int i = 0; i < 5; i++) {
                SemaphoreStep.success("s" + i + "/1", null);
            }
            WorkflowRun b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getLastBuild();
            // Verify that all the waiting happens in parallel, not serially:
            for (int i = 0; i < 5; i++) {
                j.waitForMessage("Waiting for reconnection of slave" + i + " before proceeding with build", b);
            }
            j.assertLogNotContains("assuming it is not coming back", b);
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
            for (int i = 0; i < 5; i++) {
                j.assertLogContains("slave" + i + " has been removed for 15 sec, assuming it is not coming back", b);
            }
            assertThat(logging.getRecords().stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toArray(), emptyArray());
        });
    }

    @Issue("JENKINS-69936")
    @Test public void nestedNode() throws Throwable {
        sessions.then(j -> {
            logging.record(ExecutorStepDynamicContext.class, Level.FINE).record(FilePathDynamicContext.class, Level.FINE);
            DumbSlave alpha = j.createSlave("alpha", null, null);
            DumbSlave beta = j.createSlave("beta", null, null);
            j.waitOnline(alpha);
            j.waitOnline(beta);
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {echo(/here ${pwd()}/)}}", true));
            j.assertLogContains("here " + beta.getWorkspaceFor(p).getRemote(), j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {ws('alphadir') {node('beta') {echo(/here ${pwd()}/)}}}", true));
            j.assertLogContains("here " + beta.getWorkspaceFor(p).getRemote(), j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {ws('betadir') {echo(/here ${pwd()}/)}}}", true));
            j.assertLogContains("here " + beta.getRootPath().child("betadir").getRemote(), j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {dir('alphadir') {node('beta') {echo(/here ${pwd()}/)}}}", true));
            j.assertLogContains("here " + beta.getWorkspaceFor(p).getRemote(), j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {dir('betadir') {echo(/here ${pwd()}/)}}}", true));
            j.assertLogContains("here " + beta.getWorkspaceFor(p).child("betadir").getRemote(), j.buildAndAssertSuccess(p));
        });
    }

    @Issue("JENKINS-70528")
    @Test public void nestedNodeSameAgent() throws Throwable {
        sessions.then(j -> {
            logging.record(ExecutorStepDynamicContext.class, Level.FINE).record(FilePathDynamicContext.class, Level.FINE);
            DumbSlave big = new DumbSlave("big", new File(j.jenkins.getRootDir(), "agent-work-dirs/big").getAbsolutePath(), j.createComputerLauncher(null));
            big.setLabelString("alpha beta");
            big.setNumExecutors(2);
            j.jenkins.addNode(big);
            j.waitOnline(big);
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {echo(/here ${pwd()}!/)}}", true));
            j.assertLogContains("here " + big.getWorkspaceFor(p).getRemote() + "@2!", j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {ws('alphadir') {node('beta') {echo(/here ${pwd()}!/)}}}", true));
            j.assertLogContains("here " + big.getWorkspaceFor(p).getRemote() + "@2!", j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {ws('betadir') {echo(/here ${pwd()}!/)}}}", true));
            j.assertLogContains("here " + big.getRootPath().child("betadir").getRemote() + "!", j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {dir('alphadir') {node('beta') {echo(/here ${pwd()}!/)}}}", true));
            j.assertLogContains("here " + big.getWorkspaceFor(p).getRemote() + "@2!", j.buildAndAssertSuccess(p));
            p.setDefinition(new CpsFlowDefinition("node('alpha') {node('beta') {dir('betadir') {echo(/here ${pwd()}!/)}}}", true));
            j.assertLogContains("here " + big.getWorkspaceFor(p).sibling(big.getWorkspaceFor(p).getBaseName() + "@2").child("betadir").getRemote() + "!", j.buildAndAssertSuccess(p));
        });
    }

    @Test public void onceRetentionStrategyNodeDisappearance() throws Throwable {
        commonSetup();
        sessions.then(j -> {
            DumbSlave s = j.createSlave(Label.get("ghost"));
            s.setRetentionStrategy(new OnceRetentionStrategy(0));
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('ghost') {if (isUnix()) {sh 'sleep infinity'} else {bat 'echo + sleep infinity && ping -n 999999 localhost'}}", true));
            var run = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("+ sleep infinity", run);
            j.jenkins.removeNode(s);
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
            assertThat(j.jenkins.getQueue().getItems(), emptyArray());
            InterruptedBuildAction iba = run.getAction(InterruptedBuildAction.class);
            assertNotNull(iba);
            assertThat(iba.getCauses(), contains(isA(ExecutorStepExecution.RemovedNodeCause.class)));
        });
    }

    @Test public void cloudNodeDisappearance() throws Throwable {
        commonSetup();
        sessions.then(j -> {
            var mockCloud = new MockCloud("mock");
            mockCloud.setLabels("mock");
            j.jenkins.clouds.add(mockCloud);
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('mock') {if (isUnix()) {sh 'sleep infinity'} else {bat 'echo + sleep infinity && ping -n 999999 localhost'}}", true));
            WorkflowRun run = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("+ sleep infinity", run);
            var mockNodes = j.jenkins.getLabel("mock").getNodes();
            assertThat(mockNodes, hasSize(1));
            var mockNode = mockNodes.iterator().next();
            j.jenkins.removeNode(mockNode);
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
            assertThat(j.jenkins.getQueue().getItems(), emptyArray());
            InterruptedBuildAction iba = run.getAction(InterruptedBuildAction.class);
            assertNotNull(iba);
            assertThat(iba.getCauses(), contains(isA(ExecutorStepExecution.RemovedNodeCause.class)));
        });
    }
}
