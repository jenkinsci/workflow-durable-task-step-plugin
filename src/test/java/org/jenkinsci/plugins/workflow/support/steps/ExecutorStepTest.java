/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
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

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.WorkspaceListener;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Launcher;
import hudson.remoting.Which;
import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.tools.ant.util.JavaEnvUtils;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import static org.junit.Assert.*;

/** Tests pertaining to {@code node} and {@code sh} steps. */
public class ExecutorStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Executes a shell script build on a slave.
     *
     * This ensures that the context variable overrides are working as expected, and
     * that they are persisted and resurrected.
     */
    @Test public void buildShellScriptOnSlave() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = story.j.createOnlineSlave();
                s.setLabelString("remote quick");
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    sh('echo before=`basename $PWD`')\n" +
                    "    sh('echo ONSLAVE=$ONSLAVE')\n" +
                    "    semaphore 'wait'\n" +
                    "    sh('echo after=$PWD')\n" +
                    "}"));

                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = (WorkflowJob) story.j.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));

                story.j.assertLogContains("before=demo", b);
                story.j.assertLogContains("ONSLAVE=true", b);

                FlowGraphWalker walker = new FlowGraphWalker(b.getExecution());
                List<WorkspaceAction> actions = new ArrayList<WorkspaceAction>();
                for (FlowNode n : walker) {
                    WorkspaceAction a = n.getAction(WorkspaceAction.class);
                    if (a != null) {
                        actions.add(a);
                    }
                }
                assertEquals(1, actions.size());
                assertEquals(new HashSet<LabelAtom>(Arrays.asList(LabelAtom.get("remote"), LabelAtom.get("quick"))), actions.get(0).getLabels());
            }
        });
    }

    @Test public void buildShellScriptOnSlaveWithDifferentResumePoint() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                String script = "node {semaphore 'wait'}";
                p.setDefinition(new CpsFlowDefinition(script));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                ((CpsFlowExecution) b.getExecutionPromise().get()).waitForSuspension();
                // intentionally not waiting for semaphore step to begin
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = (WorkflowJob) story.j.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }

    private static Process jnlpProc;
    private void startJnlpProc() throws Exception {
        killJnlpProc();
        ProcessBuilder pb = new ProcessBuilder(JavaEnvUtils.getJreExecutable("java"), "-jar", Which.jarFile(Launcher.class).getAbsolutePath(), "-jnlpUrl", story.j.getURL() + "computer/dumbo/slave-agent.jnlp");
        try {
            ProcessBuilder.class.getMethod("inheritIO").invoke(pb);
        } catch (NoSuchMethodException x) {
            // prior to Java 7
        }
        System.err.println("Running: " + pb.command());
        jnlpProc = pb.start();
    }
    // TODO @After does not seem to work at all in RestartableJenkinsRule
    @AfterClass public static void killJnlpProc() {
        if (jnlpProc != null) {
            jnlpProc.destroy();
            jnlpProc = null;
        }
    }

    @Test public void buildShellScriptAcrossRestart() throws Exception {
        story.addStep(new Statement() {
            @SuppressWarnings("SleepWhileInLoop")
            @Override public void evaluate() throws Throwable {
                Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());
                LOGGER.setLevel(Level.FINE);
                Handler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                LOGGER.addHandler(handler);
                // Cannot use regular JenkinsRule.createSlave due to JENKINS-26398.
                // Nor can we can use JenkinsRule.createComputerLauncher, since spawned commands are killed by CommandLauncher somehow (it is not clear how; apparently before its onClosed kills them off).
                DumbSlave s = new DumbSlave("dumbo", "dummy", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "", new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                story.j.jenkins.addNode(s);
                startJnlpProc();
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                assertTrue(b.isBuilding());
                killJnlpProc();
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = (WorkflowJob) story.j.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                assertTrue(b.isBuilding()); // TODO occasionally fails; log ends with: ‘Running: Allocate node : Body : Start’ (no shell step in sight)
                startJnlpProc(); // Have to relaunch JNLP agent, since the Jenkins port has changed, and we cannot force JenkinsRule to reuse the same port as before.
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("finished waiting", b);
                story.j.assertLogContains("OK, done", b);
                killJnlpProc();
            }
        });
    }

    @Test public void buildShellScriptAcrossDisconnect() throws Exception {
        story.addStep(new Statement() {
            @SuppressWarnings("SleepWhileInLoop")
            @Override public void evaluate() throws Throwable {
                Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());
                LOGGER.setLevel(Level.FINE);
                Handler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                LOGGER.addHandler(handler);
                DumbSlave s = new DumbSlave("dumbo", "dummy", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "", new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
                story.j.jenkins.addNode(s);
                startJnlpProc();
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                assertTrue(b.isBuilding());
                Computer c = s.toComputer();
                assertNotNull(c);
                killJnlpProc();
                while (c.isOnline()) {
                    Thread.sleep(100);
                }
                startJnlpProc();
                while (c.isOffline()) {
                    Thread.sleep(100);
                }
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("finished waiting", b); // TODO sometimes is not printed to log, despite f2 having been removed
                story.j.assertLogContains("OK, done", b);
                killJnlpProc();
            }
        });
    }

    @Test public void buildShellScriptQuick() throws Exception {
        final AtomicReference<String> dir = new AtomicReference<String>();
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave s = story.j.createOnlineSlave();
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                dir.set(s.getRemoteFS() + "/workspace/" + p.getFullName());
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    sh('pwd; echo ONSLAVE=$ONSLAVE')\n" +
                    "}"));

                WorkflowRun b = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));

                story.j.assertLogContains(dir.get(), b);
                story.j.assertLogContains("ONSLAVE=true", b);
            }
        });
    }

    @Test public void acquireWorkspace() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                @SuppressWarnings("deprecation")
                String slaveRoot = story.j.createTmpDir().getPath();
                story.j.jenkins.addNode(new DumbSlave("slave", "dummy", slaveRoot, "2", Node.Mode.NORMAL, "", story.j.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList()));
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                        "node('slave') {\n" + // this locks the WS
                        "    sh('echo default=`basename $PWD`')\n" +
                        "    ws {\n" + // and this locks a second one
                        "        sh('echo before=`basename $PWD`')\n" +
                        "        semaphore 'wait'\n" +
                        "        sh('echo after=`basename $PWD`')\n" +
                        "    }\n" +
                        "}"
                ));
                p.save();
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b1);
                assertTrue(b1.isBuilding());
                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/2", b2);
                assertTrue(b2.isBuilding());
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = (WorkflowJob) story.j.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                WorkflowRun b1 = p.getBuildByNumber(1);
                WorkflowRun b2 = p.getBuildByNumber(2);
                SemaphoreStep.success("wait/1", null);
                SemaphoreStep.success("wait/2", null);
                story.j.waitUntilNoActivity();
                story.j.assertBuildStatusSuccess(b1);
                story.j.assertBuildStatusSuccess(b2);
                // TODO once got ‘InvalidClassException: cannot bind non-proxy descriptor to a proxy class’ inside BourneShellScript.doLaunch
                story.j.assertLogContains("default=demo", b1);
                story.j.assertLogContains("before=demo@2", b1);
                story.j.assertLogContains("after=demo@2", b1);
                story.j.assertLogContains("default=demo@3", b2);
                story.j.assertLogContains("before=demo@4", b2);
                story.j.assertLogContains("after=demo@4", b2);
                SemaphoreStep.success("wait/3", null);
                WorkflowRun b3 = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                story.j.assertLogContains("default=demo", b3);
                story.j.assertLogContains("before=demo@2", b3);
                story.j.assertLogContains("after=demo@2", b3);
            }
        });
    }

    @Issue("JENKINS-26513")
    @Test public void executorStepRestart() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('special') {echo 'OK ran'}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                story.j.createSlave("special", null);
                WorkflowJob p = (WorkflowJob) story.j.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                // TODO JENKINS-27532 sometimes two copies of the WorkflowRun are loaded
                story.j.assertLogContains("OK ran", story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
            }
        });
    }

    @Issue("JENKINS-26130")
    @Test public void unloadableExecutorPickle() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave dumbo = story.j.createSlave("dumbo", null, null); // unlike in buildShellScriptAcrossRestart, we *want* this to die after restart
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "  semaphore 'wait'\n" +
                    "}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                dumbo.getComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(User.getUnknown(), "not about to reconnect"));
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                assertTrue(b.isBuilding());
                story.j.waitForMessage(Messages.ExecutorPickle_waiting_to_resume(Messages.ExecutorStepExecution_PlaceholderTask_displayName(b.getFullDisplayName())), b);
                story.j.waitForMessage(hudson.model.Messages.Queue_NodeOffline("dumbo"), b);
                b.getExecutor().interrupt();
                story.j.assertBuildStatus(Result.ABORTED, story.j.waitForCompletion(b));
                assertEquals(Collections.emptyList(), Arrays.asList(Queue.getInstance().getItems()));
            }
        });
    }

    @Test public void tailCall() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("def r = node {'the result'}; echo \"got ${r}\""));
                story.j.assertLogContains("got the result", story.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
                p.setDefinition(new CpsFlowDefinition("try {node {error 'a problem'}} catch (e) {echo \"failed with ${e.message}\"}"));
                story.j.assertLogContains("failed with a problem", story.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
            }
        });
    }

    @Issue("JENKINS-31649")
    @Test public void queueTaskVisibility() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                story.j.jenkins.setSecurityRealm(story.j.createDummySecurityRealm());
                story.j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
                final WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('nonexistent') {}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", b);
                ACL.impersonate(User.get("admin").impersonate(), new Runnable() {
                    @Override public void run() {
                        Queue.Item[] items = Queue.getInstance().getItems();
                        assertEquals(1, items.length); // fails in 1.638
                        assertEquals(p, items[0].task.getOwnerTask());
                    }
                });
                ACL.impersonate(User.get("devel").impersonate(), new Runnable() {
                    @Override public void run() {
                        Queue.Item[] items = Queue.getInstance().getItems();
                        assertEquals(0, items.length); // fails in 1.609.2
                    }
                });
                // TODO this would be a good time to add a third user with READ but no CANCEL permission and check behavior
                // Also try canceling the task and verify that the step aborts promptly:
                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                assertEquals(p, items[0].task.getOwnerTask());
                assertTrue(Queue.getInstance().cancel(items[0]));
                story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b));
                story.j.assertLogContains(Messages.ExecutorStepExecution_queue_task_cancelled(), b);
            }
        });
    }

    @Issue("JENKINS-30759")
    @Test public void quickNodeBlock() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("for (int i = 0; i < 50; i++) {node {echo \"ran node block #${i}\"}}"));
                story.j.assertLogContains("ran node block #49", story.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
            }
        });
    }

    @TestExtension("callWorkspaceListener")
    public final static WorkspaceListener workspaceListener = new WorkspaceListener() {
        @Override
        public void beforeUse(Run r, FilePath workspace, TaskListener listener) {
            listener.getLogger().println("WorkspaceListener has been notified");
        }
    };


    @Issue("JENKINS-35907")
    @Test public void callWorkspaceListener() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "callWorkspaceListener");
                p.setDefinition(new CpsFlowDefinition("node() { echo 'hello' }"));
                WorkflowRun b = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                story.j.assertLogContains("WorkspaceListener has been notified", b);
            }
        });
    }
}
