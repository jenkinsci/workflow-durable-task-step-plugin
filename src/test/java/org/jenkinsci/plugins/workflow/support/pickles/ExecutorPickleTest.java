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

import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.User;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;
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
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class ExecutorPickleTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule r = new RestartableJenkinsRule();
    //@Rule public LoggerRule logging = new LoggerRule().record(Queue.class, Level.FINE);

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
                // first prints on 2.35-: hudson.model.Messages.Queue_WaitingForNextAvailableExecutor(); 2.36+: hudson.model.Messages.Node_LabelMissing("Jenkins", "slave0")
                r.j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getFullDisplayName())), b);
                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                Queue.getInstance().cancel(items[0]);
                r.j.waitForCompletion(b);
                // Do not bother with assertBuildStatus; we do not really care whether it is ABORTED or FAILURE
            }
        });
    }

    @Issue("JENKINS-42556")
    @Test public void anonDiscover() {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                r.j.jenkins.setSecurityRealm(r.j.createDummySecurityRealm());
                r.j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                    grant(Jenkins.ADMINISTER).everywhere().to("admin").
                    grant(Jenkins.READ, Item.DISCOVER).everywhere().toEveryone());
                r.j.jenkins.save(); // TODO pending https://github.com/jenkinsci/jenkins/pull/2790
                DumbSlave remote = r.j.createSlave("remote", null, null);
                WorkflowJob p = r.j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node('remote') {semaphore 'wait'}", true));
                SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
                remote.toComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(User.get("admin"), "hold"));
            }
        });
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                SemaphoreStep.success("wait/1", null);
                WorkflowJob p = r.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                assertFalse(p.getACL().hasPermission(Jenkins.ANONYMOUS, Item.READ));
                WorkflowRun b = p.getBuildByNumber(1);
                r.j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getFullDisplayName())), b);
                r.j.jenkins.getNode("remote").toComputer().setTemporarilyOffline(false, null);
                r.j.assertBuildStatusSuccess(r.j.waitForCompletion(b));
            }
        });
    }

}
