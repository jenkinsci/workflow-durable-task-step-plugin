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

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.remoting.Launcher;
import hudson.remoting.Which;
import hudson.slaves.ComputerListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.apache.tools.ant.util.JavaEnvUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

public class ExecutorPickleTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule r = new RestartableJenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    //@Rule public LoggerRule logging = new LoggerRule().record(Queue.class, Level.FINE);

    private static Process jnlpProc;
    private void startJnlpProc() throws Exception {
        killJnlpProc();
        ProcessBuilder pb = new ProcessBuilder(JavaEnvUtils.getJreExecutable("java"), "-jar", Which.jarFile(Launcher.class).getAbsolutePath(), "-jnlpUrl", r.j.getURL() + "computer/ghostly/slave-agent.jnlp");
        try {
            ProcessBuilder.class.getMethod("inheritIO").invoke(pb);
        } catch (NoSuchMethodException x) {
            // prior to Java 7
        }
        System.err.println("Running: " + pb.command());
        jnlpProc = pb.start();
    }

    @AfterClass
    public static void killJnlpProc() {
        if (jnlpProc != null) {
            jnlpProc.destroy();
            jnlpProc = null;
        }
    }

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
                r.j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(
                        Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getParent().getName())), b);
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
                r.j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(
                		Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getParent().getName())), b);
                r.j.jenkins.getNode("remote").toComputer().setTemporarilyOffline(false, null);
                r.j.assertBuildStatusSuccess(r.j.waitForCompletion(b));
            }
        });
    }

    /** Ephemeral and deleted when it disconnects */
    static class EphemeralDumbAgent extends Slave implements EphemeralNode {

        public EphemeralDumbAgent(JenkinsRule rule, String labels) throws Exception {
            super("ghostly", "I disappear", rule.createTmpDir().getPath(), "1", Node.Mode.NORMAL, labels, rule.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.EMPTY_LIST);
        }

        public void addIfMissingAndWaitForOnline(JenkinsRule rule) throws InterruptedException {
            if (rule.jenkins.getNode(this.getNodeName()) == null) {
                try {
                    rule.jenkins.addNode(this);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            } else if (rule.jenkins.getNode(this.getNodeName()).toComputer().isOnline()) {
                return;  // Already online
            }
            final CountDownLatch latch = new CountDownLatch(1);
            ComputerListener waiter = new ComputerListener() {
                @Override
                public void onOnline(Computer C, TaskListener t) {
                    if (EphemeralDumbAgent.super.toComputer() == C) {
                        latch.countDown();
                        unregister();
                    } else {
                        System.out.println("Saw an irrelevant node go online.");
                    }
                }
            };
            waiter.register();
            latch.await();
        }

        @Extension
        public static final class DescriptorImpl extends SlaveDescriptor {
            public String getDisplayName() {
                return hudson.slaves.Messages.DumbSlave_displayName();
            }
        }

        @Override
        public Node asNode() {
            return this;
        }
    }

    /**
     * Test that {@link ExecutorPickle} won't spin forever trying to rehydrate if it was using an
     *  node that disappeared and will never reappear... but still waits a little bit to find out.
     *
     *  I.E. cases where the {@link RetentionStrategy} is {@link RetentionStrategy#NOOP}.
     */
    @Issue("JENKINS-36013")
    @Test public void normalNodeDisappearance() throws Exception {
        r.addStep(new Statement() {
            // Start up a build that needs executor and then reboot and take the node offline
            @Override public void evaluate() throws Throwable {
                // Starting job first ensures we don't immediately fail if Node comes from a Cloud
                //  and takes a min to provision
                WorkflowJob p = r.j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node('ghost') {semaphore 'wait'}", true));

                DumbSlave s = new DumbSlave("ghostly", "dummy", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "ghost", new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                r.j.jenkins.addNode(s);
                startJnlpProc();
                r.j.jenkins.save();
                System.out.println("Agent launched, waiting for semaphore");
                SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
                ExecutorPickle.TIMEOUT_WAITING_FOR_NODE_MILLIS = 4000L; // fail faster
                killJnlpProc();
                r.j.jenkins.removeNode(s);
            }
        });

        r.addStep(new Statement() {
            // Start up a build and then reboot and take the node offline
            @Override public void evaluate() throws Throwable {
                ExecutorPickle.TIMEOUT_WAITING_FOR_NODE_MILLIS = 4000L; // fail faster
                assertEquals(0, r.j.jenkins.getLabel("ghost").getNodes().size()); // Make sure test impl is correctly deleted
                assertNull(r.j.jenkins.getNode("ghost")); // Make sure test impl is correctly deleted
                WorkflowRun run = r.j.jenkins.getItemByFullName("p", WorkflowJob.class).getLastBuild();
                r.j.waitForMessage("Waiting to resume", run);
                Thread.sleep(1000L);
                Assert.assertTrue(run.isBuilding());
                Assert.assertEquals("Queue should still have single build Item waiting to resume but didn't", 1, Queue.getInstance().getItems().length);

                try {
                    Thread.sleep(ExecutorPickle.TIMEOUT_WAITING_FOR_NODE_MILLIS + 1000L);
                    Assert.assertEquals("Should have given up and killed the Task representing the resuming build", 0, Queue.getInstance().getItems().length );
                    Assert.assertFalse(run.isBuilding());
                    r.j.assertBuildStatus(Result.FAILURE, run);
                    Assert.assertEquals(0, r.j.jenkins.getQueue().getItems().length);
                } catch (InterruptedIOException ioe) {
                    Assert.fail("Waited for build to detect loss of node and it didn't!");
                } finally {
                    if (run.isBuilding()) {
                        run.doKill();
                    }
                }
            }
        });
    }

}
