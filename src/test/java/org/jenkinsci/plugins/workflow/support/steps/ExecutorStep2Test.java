/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
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

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.remoting.Channel;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import jenkins.model.Jenkins;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.SimpleCommandLauncher;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * Like {@link ExecutorStepTest} but not using {@link Parameterized} which appears incompatible with {@link TestExtension}.
 */
class ExecutorStep2Test {

    @RegisterExtension
    private static final BuildWatcherExtension buildWatcher = new BuildWatcherExtension();
    @RegisterExtension
    private final JenkinsSessionExtension rr = new JenkinsSessionExtension();
    private final LogRecorder logging = new LogRecorder();

    @Issue("JENKINS-53837")
    @Test
    void queueTaskOwnerCorrectWhenRestarting() throws Throwable {
        rr.then(r -> {
            ExtensionList.lookupSingleton(PipelineOnlyTaskDispatcher.class);
            WorkflowJob p = r.createProject(WorkflowJob.class, "p1");
            p.setDefinition(new CpsFlowDefinition("""
                node {
                  semaphore('wait')
                }""", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
        });
        rr.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p1", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            SemaphoreStep.success("wait/1", null);
            r.waitForCompletion(b);
            r.assertBuildStatusSuccess(b);
            r.assertLogNotContains("Non-Pipeline tasks are forbidden!", b);
        });
    }
    @TestExtension("queueTaskOwnerCorrectWhenRestarting")
    public static class PipelineOnlyTaskDispatcher extends QueueTaskDispatcher {
        @Override
        public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            Queue.Task t = item.task;
            while (!(t instanceof Item) && (t != null)) {
                final Queue.Task ownerTask = t.getOwnerTask();
                if (t == ownerTask) {
                    break;
                }
                t = ownerTask;
            }
            if (t instanceof WorkflowJob) {
                return null;
            }
            final Queue.Task finalT = t;
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "Non-Pipeline tasks are forbidden! Not building: " + finalT;
                }
            };
        }
    }

    @Test
    void cloud() throws Throwable {
        rr.then(r -> {
            ExtensionList.lookupSingleton(TestCloud.DescriptorImpl.class);
            r.jenkins.clouds.add(new TestCloud());
            var p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('test') {}", true));
            logging.record(OnceRetentionStrategy.class, Level.FINE).record(ExecutorStepExecution.class, Level.FINE);
            r.assertLogContains("Running on test-1", r.buildAndAssertSuccess(p));
            await("waiting for test-1 to be removed").until(r.jenkins::getNodes, empty());
            r.assertLogContains("Running on test-2", r.buildAndAssertSuccess(p));
            await("waiting for test-2 to be removed").until(r.jenkins::getNodes, empty());
        });
    }
    // adapted from org.jenkinci.plugins.mock_slave.MockCloud
    public static final class TestCloud extends Cloud {
        TestCloud() {
            super("test");
        }
        @Override public boolean canProvision(Cloud.CloudState state) {
            var label = state.getLabel();
            return label != null && label.matches(Label.parse("test"));
        }
        @Override public Collection<NodeProvisioner.PlannedNode> provision(Cloud.CloudState state, int excessWorkload) {
            var r = new ArrayList<NodeProvisioner.PlannedNode>();
            while (excessWorkload > 0) {
                r.add(new NodeProvisioner.PlannedNode("test", Computer.threadPoolForRemoting.submit(() -> new TestCloudSlave()), 1));
                excessWorkload -= 1;
            }
            return r;
        }
        @TestExtension("cloud") public static final class DescriptorImpl extends Descriptor<Cloud> {
            private long counter;
            public DescriptorImpl() {
                load();
                NodeProvisioner.NodeProvisionerInvoker.INITIALDELAY = 1000;
                NodeProvisioner.NodeProvisionerInvoker.RECURRENCEPERIOD = 1000;
            }
            synchronized long newNodeNumber() {
                counter++;
                save();
                return counter;
            }
        }
        private static final class TestCloudSlave extends AbstractCloudSlave {
            TestCloudSlave() throws Exception {
                this("test-" + ExtensionList.lookupSingleton(TestCloud.DescriptorImpl.class).newNodeNumber());
            }
            private TestCloudSlave(String name) throws Exception {
                super(name, new File(new File(Jenkins.get().getRootDir(), "agents"), name).getAbsolutePath(),
                        new SimpleCommandLauncher(String.format("\"%s/bin/java\" -jar \"%s\"",
                            System.getProperty("java.home"),
                            new File(Jenkins.get().getJnlpJars("agent.jar").getURL().toURI()))));
                setMode(Node.Mode.EXCLUSIVE);
                setNumExecutors(1);
                setLabelString("test");
                setRetentionStrategy(new OnceRetentionStrategy(1));
            }
            @Override public AbstractCloudComputer<?> createComputer() {
                return new AbstractCloudComputer<>(this);
            }
            @Override protected void _terminate(TaskListener listener) {}
            @TestExtension("cloud") public static final class DescriptorImpl extends Slave.SlaveDescriptor {
                @Override public boolean isInstantiable() {
                    return false;
                }
            }
        }
    }

    @Test
    void selfNameVsLabel() throws Throwable {
        logging.recordPackage(ExecutorStepExecution.class, Level.FINE);
        rr.then(r -> {
            r.jenkins.setNumExecutors(0);
            ExtensionList.lookupSingleton(DelayX.class).active = false;
            r.createSlave("x", null, null);
            r.createSlave("x2", "x", null);
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("parallel b1: {node('x && !x2') {semaphore 'b1'}}, b2: {node('x2') {semaphore 'b2'}}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("b1/1", b);
            SemaphoreStep.waitForStart("b2/1", b);
        });
        rr.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            SemaphoreStep.success("b1/1", null);
            SemaphoreStep.success("b2/1", null);
            r.waitForCompletion(b);
            r.assertBuildStatusSuccess(b);
        });
    }
    @TestExtension("selfNameVsLabel") public static final class DelayX extends ComputerListener {
        boolean active = true;
        @Override public void preOnline(Computer c, Channel channel, FilePath root, TaskListener listener) throws IOException, InterruptedException {
            if (c.getName().equals("x")) {
                Thread.sleep(5_000);
            }
        }
    }

}
