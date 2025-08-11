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

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.common.base.Predicate;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Queue.LeftItem;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.User;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.WorkspaceList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.InboundAgentExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;
import org.springframework.security.core.Authentication;
import org.apache.commons.io.IOUtils;
import org.htmlunit.Page;
import org.jenkinsci.plugins.durabletask.FileMonitoringTask;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepTypePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.EchoStep;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;

/** Tests pertaining to {@code node} and {@code sh} steps. */
@ParameterizedClass(name = "watching={0}")
@ValueSource(booleans = {true, false})
class ExecutorStepTest {

    private static final Logger LOGGER = Logger.getLogger(ExecutorStepTest.class.getName());

    @Parameter
    private boolean useWatching;

    @RegisterExtension
    private static final BuildWatcherExtension buildWatcher = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();
    @RegisterExtension
    private final InboundAgentExtension inboundAgents = new InboundAgentExtension();
    @TempDir
    private File tmp;
    // Currently too noisy due to unrelated warnings; might clear up if test dependencies updated: .record(ExecutorStepExecution.class, Level.FINE)
    private final LogRecorder logging = new LogRecorder();
    private String nodeTimeout;

    @BeforeEach
    void setUp() {
        DurableTaskStep.USE_WATCHING = useWatching;
        nodeTimeout = System.getProperty("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis");
    }

    @AfterEach
    void tearDown() {
        if (nodeTimeout != null) {
            System.setProperty("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis", nodeTimeout);
        } else {
           System.clearProperty("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis");
        }
    }

    /**
     * Executes a shell script build on a build agent.
     *
     * This ensures that the context variable overrides are working as expected, and
     * that they are persisted and resurrected.
     */
    @Test
    void buildShellScriptOnSlave() throws Throwable {
        sessions.then(r -> {
                DumbSlave s = r.createSlave("remote quick", null);
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    isUnix() ? sh('echo ONSLAVE=$ONSLAVE') : bat('echo ONSLAVE=%ONSLAVE%')\n" +
                    "    semaphore 'wait'\n" +
                    "}", true));

                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
        });
        sessions.then(r -> {
                WorkflowJob p = (WorkflowJob) r.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait/1", null);
                r.assertBuildStatusSuccess(r.waitForCompletion(b));

                r.assertLogContains("ONSLAVE=true", b);

                FlowGraphWalker walker = new FlowGraphWalker(b.getExecution());
                List<WorkspaceAction> actions = new ArrayList<>();
                for (FlowNode n : walker) {
                    WorkspaceAction a = n.getAction(WorkspaceAction.class);
                    if (a != null) {
                        actions.add(a);
                    }
                }
                assertEquals(1, actions.size());
                assertEquals(new HashSet<>(Arrays.asList(LabelAtom.get("remote"), LabelAtom.get("quick"))), actions.get(0).getLabels());
        });
    }

    /**
     * Executes a shell script build on a build agent and ensures the processes are
     * killed at the end of the run
     *
     * This ensures that the context variable overrides are working as expected, and
     * that they are persisted and resurrected.
     */
    @Test
    void buildShellScriptWithPersistentProcesses() throws Throwable {
        sessions.then(r -> {
                DumbSlave s = r.createSlave();
                Path f1 = r.jenkins.getRootDir().toPath().resolve("test.txt");
                String fullPathToTestFile = f1.toAbsolutePath().toString();
                // Escape any \ in the source so that the script is valid
                fullPathToTestFile = fullPathToTestFile.replace("\\", "\\\\");
                // Ensure deleted, perhaps if this test previously failed using the same workspace
                Files.deleteIfExists(f1);

                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                // We use sleep on Unix.  On Windows, timeout would
                // be the equivalent, but it uses input redirection which is
                // not supported.  So instead use ping.
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    isUnix() ? sh('(sleep 5; touch " + fullPathToTestFile + ") &') : bat('start /B cmd.exe /C \"ping localhost -n 5 && copy NUL " + fullPathToTestFile + "\"')\n" +
                    "}", true));
                WorkflowRun b = r.buildAndAssertSuccess(p);

                // Wait until the build completes.
                r.waitForCompletion(b);
                // Then wait additionally for 10 seconds to make sure that the sleep
                // steps would have exited
                Thread.sleep(10000);
                // Then check for existence of the file
                assertFalse(Files.exists(f1));
        });
    }

    @Test
    void buildShellScriptAcrossRestart() throws Throwable {
        assumeFalse(Functions.isWindows(), "TODO not sure how to write a corresponding batch script");
        sessions.then(r -> {
                logging.record(DurableTaskStep.class, Level.FINE).record(FileMonitoringTask.class, Level.FINE).record(ExecutorStepDynamicContext.class, Level.FINE).record(ExecutorStepExecution.class, Level.FINE);
                DumbSlave s = r.createSlave("dumbo", null, null);
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                File f1 = new File(r.jenkins.getRootDir(), "f1");
                File f2 = new File(r.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do echo waiting; sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                r.waitForMessage("waiting", b);
                assertTrue(b.isBuilding());
        });
        sessions.then(r -> {
                WorkflowJob p = (WorkflowJob) r.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                assertTrue(b.isBuilding()); // TODO occasionally fails; log ends with: ‘Running: Allocate node : Body : Start’ (no shell step in sight)
                File f1 = new File(r.jenkins.getRootDir(), "f1");
                File f2 = new File(r.jenkins.getRootDir(), "f2");
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                r.assertBuildStatusSuccess(r.waitForCompletion(b));
                r.assertLogContains("finished waiting", b);
                r.assertLogContains("OK, done", b);
        });
    }

    @Issue("JENKINS-52165")
    @Test
    void shellOutputAcrossRestart() throws Throwable {
        assumeFalse(Functions.isWindows(), "TODO not sure how to write a corresponding batch script");
        // TODO does not assert anything in watch mode, just informational.
        // There is no way for FileMonitoringTask.Watcher to know when content has been written through to the sink
        // other than by periodically flushing output and declining to write lastLocation until after this completes.
        // This applies both to buffered on-master logs, and to typical cloud sinks.
        logging.record(DurableTaskStep.class, Level.FINE).record(FileMonitoringTask.class, Level.FINE);
        int count = 3_000;
        sessions.then(r -> {
            DumbSlave s = r.createSlave("dumbo", null, null);
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('dumbo') {sh 'set +x; i=0; while [ $i -lt " + count + " ]; do echo \"<<<$i>>>\"; sleep .01; i=`expr $i + 1`; done'}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("\n<<<" + (count / 3) + ">>>\n", b);
            s.toComputer().disconnect(null);
        });
        sessions.then(r -> {
            WorkflowRun b = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            // Paying attention to the per-node log rather than whole-build log to exclude issues with copyLogs prior to JEP-210:
            FlowNode shNode = new DepthFirstScanner().findFirstMatch(b.getExecution(), new NodeStepTypePredicate("sh"));
            String log = IOUtils.toString(shNode.getAction(LogAction.class).getLogText().readAll());
            int lost = 0;
            for (int i = 0; i < count; i++) {
                if (!log.contains("\n<<<" + i + ">>>\n")) {
                    lost++;
                }
            }
            Matcher m = Pattern.compile("<<<\\d+>>>").matcher(log);
            int seen = 0;
            while (m.find()) {
                seen++;
            }
            System.out.printf("Lost content: %.02f%%%n", lost * 100.0 / count);
            System.out.printf("Duplicated content: %.02f%%%n", (seen - count) * 100.0 / count);
        });
    }

    @Test
    void buildShellScriptAcrossDisconnect() throws Throwable {
        assumeFalse(Functions.isWindows(), "TODO not sure how to write a corresponding batch script");
        sessions.then(r -> {
                logging.record(DurableTaskStep.class, Level.FINE).record(FileMonitoringTask.class, Level.FINE);
                Slave s = inboundAgents.createAgent(r, "dumbo");
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                File f1 = new File(r.jenkins.getRootDir(), "f1");
                File f2 = new File(r.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                    "node('dumbo') {\n" +
                    "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do echo waiting; sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                    "    echo 'OK, done'\n" +
                    "}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                r.waitForMessage("waiting", b);
                assertTrue(b.isBuilding());
                Computer c = s.toComputer();
                assertNotNull(c);
                inboundAgents.stop("dumbo");
                while (c.isOnline()) {
                    Thread.sleep(100);
                }
                inboundAgents.start(r, "dumbo");
                while (c.isOffline()) {
                    Thread.sleep(100);
                }
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                r.assertBuildStatusSuccess(r.waitForCompletion(b));
                r.assertLogContains("finished waiting", b); // TODO sometimes is not printed to log, despite f2 having been removed
                r.assertLogContains("OK, done", b);
        });
    }

    @Issue({"JENKINS-41854", "JENKINS-50504"})
    @Test
    void contextualizeFreshFilePathAfterAgentReconnection() throws Throwable {
        assumeFalse(Functions.isWindows(), "TODO not sure how to write a corresponding batch script");
        sessions.then(r -> {
                logging.record(DurableTaskStep.class, Level.FINE).
                        record(ExecutorStepDynamicContext.class, Level.FINE).
                        record(FileMonitoringTask.class, Level.FINEST).
                        record(WorkspaceList.class, Level.FINE);
                Slave s = inboundAgents.createAgent(r, "dumbo");
                r.showAgentLogs(s, Map.of(DurableTaskStep.class.getName(), Level.FINE, ExecutorStepDynamicContext.class.getName(), Level.FINE,
                    FileMonitoringTask.class.getName(), Level.FINEST, WorkspaceList.class.getName(), Level.FINE));
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                File f1 = new File(r.jenkins.getRootDir(), "f1");
                File f2 = new File(r.jenkins.getRootDir(), "f2");
                new FileOutputStream(f1).close();
                p.setDefinition(new CpsFlowDefinition(
                        "node('dumbo') {\n" +
                                "    sh 'touch \"" + f2 + "\"; while [ -f \"" + f1 + "\" ]; do echo waiting; sleep 1; done; echo finished waiting; rm \"" + f2 + "\"'\n" +
                                "    sh 'echo Back again'\n" +
                                "    echo 'OK, done'\n" +
                                "}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                LOGGER.info("build started");
                while (!f2.isFile()) {
                    Thread.sleep(100);
                }
                r.waitForMessage("waiting", b);
                LOGGER.info("f2 created, first sh running");
                assertTrue(b.isBuilding());
                Computer computer = s.toComputer();
                assertNotNull(computer);
                FlowGraphWalker walker = new FlowGraphWalker(b.getExecution());
                List<WorkspaceAction> actions = new ArrayList<>();
                for (FlowNode node : walker) {
                    WorkspaceAction action = node.getAction(WorkspaceAction.class);
                    if (action != null) {
                        actions.add(action);
                    }
                }
                assertEquals(1, actions.size());
                String workspacePath = actions.get(0).getWorkspace().getRemote();
                assertWorkspaceLocked(computer, workspacePath);
                LOGGER.info("killing agent");
                inboundAgents.stop("dumbo");
                long lastMessageMillis = System.currentTimeMillis();
                while (computer.isOnline()) {
                    if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastMessageMillis) > 30) {
                        LOGGER.info(() -> "Waiting for " + computer.getNode() + " to go offline. JNLP Process is " + (inboundAgents.isAlive("dumbo") ? "alive" : "not alive"));
                        lastMessageMillis = System.currentTimeMillis();
                    }
                    Thread.sleep(100);
                }
                LOGGER.info("restarting agent");
                inboundAgents.start(r, "dumbo");
                while (computer.isOffline()) {
                    Thread.sleep(100);
                }
                LOGGER.info("agent back online");
                r.showAgentLogs(s, Map.of(DurableTaskStep.class.getName(), Level.FINE, ExecutorStepDynamicContext.class.getName(), Level.FINE,
                    FileMonitoringTask.class.getName(), Level.FINEST, WorkspaceList.class.getName(), Level.FINE));
                assertWorkspaceLocked(computer, workspacePath);
                assertTrue(f2.isFile());
                assertTrue(f1.delete());
                while (f2.isFile()) {
                    Thread.sleep(100);
                }
                LOGGER.info("f2 deleted, first sh finishing");
                r.assertBuildStatusSuccess(r.waitForCompletion(b));
                r.assertLogContains("finished waiting", b);
                r.assertLogContains("Back again", b);
                r.assertLogContains("OK, done", b);
        });
    }

    private static void assertWorkspaceLocked(Computer computer, String workspacePath) throws InterruptedException {
        FilePath proposed = new FilePath(computer.getChannel(), workspacePath);
        try (WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(proposed)) {
            assertNotEquals(workspacePath, lease.path.getRemote());
        }
    }

    @Test
    void buildShellScriptQuick() throws Throwable {
        sessions.then(r -> {
                DumbSlave s = r.createSlave();
                s.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("ONSLAVE", "true")));

                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "node('" + s.getNodeName() + "') {\n" +
                    "    isUnix() ? sh('echo ONSLAVE=$ONSLAVE') : bat('echo ONSLAVE=%ONSLAVE%')\n" +
                    "}", true));

                WorkflowRun b = r.buildAndAssertSuccess(p);
                r.assertLogContains("ONSLAVE=true", b);
        });
    }

    @Test
    void acquireWorkspace() throws Throwable {
        sessions.then(r -> {
                String slaveRoot = newFolder(tmp, "junit").getPath();
                DumbSlave s = new DumbSlave("agent", slaveRoot, r.createComputerLauncher(null));
                s.setNumExecutors(2);
                s.setRetentionStrategy(RetentionStrategy.NOOP);
                r.jenkins.addNode(s);
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                        "node('agent') {\n" + // this locks the WS
                        "    echo(/default=${pwd()}/)\n" +
                        "    ws {\n" + // and this locks a second one
                        "        echo(/before=${pwd()}/)\n" +
                        "        semaphore 'wait'\n" +
                        "        echo(/after=${pwd()}/)\n" +
                        "    }\n" +
                        "}"
                , true));
                p.save();
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b1);
                assertTrue(b1.isBuilding());
                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/2", b2);
                assertTrue(b2.isBuilding());
        });
        logging.record(WorkspaceStepExecution.class, Level.FINE);
        logging.record(FilePathDynamicContext.class, Level.FINE);
        sessions.then(r -> {
                WorkflowJob p = (WorkflowJob) r.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                WorkflowRun b1 = p.getBuildByNumber(1);
                WorkflowRun b2 = p.getBuildByNumber(2);
                SemaphoreStep.success("wait/1", null);
                SemaphoreStep.success("wait/2", null);
                r.waitUntilNoActivity();
                r.assertBuildStatusSuccess(b1);
                r.assertBuildStatusSuccess(b2);
                assertLogMatches(b1, "^default=.+demo$");
                assertLogMatches(b1, "^before=.+demo@2$");
                assertLogMatches(b1, "^after=.+demo@2$");
                assertLogMatches(b2, "^default=.+demo@3$");
                assertLogMatches(b2, "^before=.+demo@4$");
                assertLogMatches(b2, "^after=.+demo@4$");
                SemaphoreStep.success("wait/3", null);
                WorkflowRun b3 = r.buildAndAssertSuccess(p);
                assertLogMatches(b3, "^default=.+demo$");
                assertLogMatches(b3, "^before=.+demo@2$");
                assertLogMatches(b3, "^after=.+demo@2$");
        });
    }

    private static void assertLogMatches(WorkflowRun build, String regexp) throws IOException { // TODO add to JenkinsRule
        String log = JenkinsRule.getLog(build);
        if (!Pattern.compile(regexp, Pattern.MULTILINE).matcher(log).find()) { // assertMatches present in some utility extension to JUnit/Hamcrest but not in our test CP
            fail(build + " log does not match /" + regexp + "/: " + log);
        }
    }

    @Issue("JENKINS-26513")
    @Test
    void executorStepRestart() throws Throwable {
        sessions.then(r -> {
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('special') {echo 'OK ran'}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                r.waitForMessage("Still waiting to schedule task", b);
        });
        sessions.then(r -> {
                r.createSlave("special", null);
                WorkflowJob p = (WorkflowJob) r.jenkins.getItem("demo");
                WorkflowRun b = p.getLastBuild();
                r.assertLogContains("OK ran", r.assertBuildStatusSuccess(r.waitForCompletion(b)));
        });
    }

    @Issue("JENKINS-26130")
    @Test
    void unrestorableAgent() throws Throwable {
        sessions.then(r -> {
                DumbSlave dumbo = r.createSlave("dumbo", null, null);
                WorkflowJob p = r.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    """
                        node('dumbo') {
                          semaphore 'wait'
                        }""", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                dumbo.getComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(User.getUnknown(), "not about to reconnect"));
        });
        sessions.then(r -> {
                WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                assertTrue(b.isBuilding());
                SemaphoreStep.success("wait/1", null);
                r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
                assertEquals(Collections.emptyList(), Arrays.asList(Queue.getInstance().getItems()));
                r.assertLogContains("dumbo has been removed for 15 sec; assuming it is not coming back, and terminating node step", b);
        });
    }

    @Test
    void detailsExported() throws Throwable {
        sessions.then(r -> {
                DumbSlave s = r.createSlave();

                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                        "node('" + s.getNodeName() + "') {\n"
                        + "semaphore 'wait'\n"
                        + "    sleep 10\n"
                        + "}", true));

                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                JenkinsRule.WebClient wc = r.createWebClient();
                Page page = wc
                        .goTo("computer/" + s.getNodeName()
                                + "/api/json?tree=executors[currentExecutable[number,displayName,fullDisplayName,url,timestamp]]", "application/json");

                JSONObject propertiesJSON = JSONObject.fromObject(page.getWebResponse().getContentAsString());
                JSONArray executors = propertiesJSON.getJSONArray("executors");
                JSONObject executor = executors.getJSONObject(0);
                JSONObject currentExecutable = executor.getJSONObject("currentExecutable");

                assertEquals(1, currentExecutable.get("number"));

                assertEquals("part of " + b.getFullDisplayName(),
                        currentExecutable.get("displayName"));

                assertEquals("part of " + p.getFullDisplayName() + " #1",
                        currentExecutable.get("fullDisplayName"));

                assertEquals(r.getURL().toString() + "job/" + p.getName() + "/1/",
                        currentExecutable.get("url"));
        });
    }

    @Test
    void tailCall() throws Throwable {
        sessions.then(r -> {
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("def r = node {'the result'}; echo \"got ${r}\"", true));
                r.assertLogContains("got the result", r.buildAndAssertSuccess(p));
                p.setDefinition(new CpsFlowDefinition("try {node {error 'a problem'}} catch (e) {echo \"failed with ${e.message}\"}", true));
                r.assertLogContains("failed with a problem", r.buildAndAssertSuccess(p));
        });
    }

    @Issue("JENKINS-31649")
    @Test
    void queueTaskVisibility() throws Throwable {
        sessions.then(r -> {
                r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
                r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
                final WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('nonexistent') {}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                r.waitForMessage("Still waiting to schedule task", b);
                try (ACLContext context = ACL.as(User.getById("admin", true))) {
                    Queue.Item[] items = Queue.getInstance().getItems();
                    assertEquals(1, items.length); // fails in 1.638
                    assertEquals(p, items[0].task.getOwnerTask());
                }
                try (ACLContext context = ACL.as(User.getById("devel", true))) {
                    Queue.Item[] items = Queue.getInstance().getItems();
                    assertEquals(0, items.length); // fails in 1.609.2
                }
                // TODO this would be a good time to add a third user with READ but no CANCEL permission and check behavior
                // Also try canceling the task and verify that the step aborts promptly:
                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                assertEquals(p, items[0].task.getOwnerTask());
                assertTrue(Queue.getInstance().cancel(items[0]));
                r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
                r.assertLogContains(Messages.ExecutorStepExecution_queue_task_cancelled(), b);
        });
    }

    @Issue("JENKINS-44981")
    @Test
    void queueItemAction() throws Throwable {
        sessions.then(r -> {
                final WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('special') {}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                r.waitForMessage("[Pipeline] node", b);

                FlowNode executorStartNode = await().until(() -> new DepthFirstScanner().findFirstMatch(b.getExecution(), new ExecutorStepWithQueueItemPredicate()), notNullValue());

                assertNotNull(executorStartNode.getAction(QueueItemAction.class));
                assertEquals(QueueItemAction.QueueState.QUEUED, QueueItemAction.getNodeState(executorStartNode));

                Queue.Item[] items = Queue.getInstance().getItems();
                assertEquals(1, items.length);
                assertEquals(p, items[0].task.getOwnerTask());
                // // TODO 2.389+ remove cast
                assertEquals(b, items[0].task.getOwnerExecutable());
                assertEquals(items[0], QueueItemAction.getQueueItem(executorStartNode));

                assertTrue(Queue.getInstance().cancel(items[0]));
                r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
                r.assertLogContains(Messages.ExecutorStepExecution_queue_task_cancelled(), b);

                FlowNode executorStartNode2 = new DepthFirstScanner().findFirstMatch(b.getExecution(), new ExecutorStepWithQueueItemPredicate());
                assertNotNull(executorStartNode2);
                assertEquals(QueueItemAction.QueueState.CANCELLED, QueueItemAction.getNodeState(executorStartNode2));
                assertInstanceOf(Queue.LeftItem.class, QueueItemAction.getQueueItem(executorStartNode2));

                // Re-run to make sure we actually get an agent and the action is set properly.
                r.createSlave("special", "special", null);

                WorkflowRun b2 = r.buildAndAssertSuccess(p);

                FlowNode executorStartNode3 = new DepthFirstScanner().findFirstMatch(b2.getExecution(), new ExecutorStepWithQueueItemPredicate());
                assertNotNull(executorStartNode3);
                assertEquals(QueueItemAction.QueueState.LAUNCHED, QueueItemAction.getNodeState(executorStartNode3));
                assertInstanceOf(Queue.LeftItem.class, QueueItemAction.getQueueItem(executorStartNode3));

                FlowNode notExecutorNode = new DepthFirstScanner().findFirstMatch(b.getExecution(), new NotExecutorStepPredicate());
                assertNotNull(notExecutorNode);
                assertEquals(QueueItemAction.QueueState.UNKNOWN, QueueItemAction.getNodeState(notExecutorNode));
        });
    }

    private static final class ExecutorStepWithQueueItemPredicate implements Predicate<FlowNode> {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            return input instanceof StepStartNode &&
                    ((StepStartNode) input).getDescriptor() == ExecutorStep.DescriptorImpl.byFunctionName("node") &&
                    input.getAction(QueueItemAction.class) != null;
        }
    }

    private static final class NotExecutorStepPredicate implements Predicate<FlowNode> {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            return input != null &&
                    input.getAction(QueueItemAction.class) == null;
        }
    }

    @Issue("JENKINS-30759")
    @Test
    void quickNodeBlock() throws Throwable {
        sessions.then(r -> {
                WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("for (int i = 0; i < 50; i++) {node {echo \"ran node block #${i}\"}}", true));
                r.assertLogContains("ran node block #49", r.buildAndAssertSuccess(p));
        });
    }


    private List<WorkspaceAction> getWorkspaceActions(WorkflowRun workflowRun) {
        FlowGraphWalker walker = new FlowGraphWalker(workflowRun.getExecution());
        List<WorkspaceAction> actions = new ArrayList<>();
        for (FlowNode n : walker) {
            WorkspaceAction a = n.getAction(WorkspaceAction.class);
            if (a != null) {
                actions.add(a);
            }
        }
        return actions;
    }

    @Issue("JENKINS-36547")
    @Test
    void reuseNodeFromPreviousRun() throws Throwable {
        sessions.then(r -> {
            createNOnlineAgentWithLabels(r, 5, "foo bar");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
            p.setDefinition(new CpsFlowDefinition("""
                node('foo') {
                }
                """, true));

            WorkflowRun run = r.buildAndAssertSuccess(p);
            List<WorkspaceAction> workspaceActions = getWorkspaceActions(run);
            assertEquals(1, workspaceActions.size());

            String firstNode = workspaceActions.get(0).getNode();
            assertNotEquals("", firstNode);

            WorkflowRun run2 = r.buildAndAssertSuccess(p);
            workspaceActions = getWorkspaceActions(run2);
            assertEquals(1, workspaceActions.size());
            assertEquals(workspaceActions.get(0).getNode(), firstNode);
        });
    }

    /**
     * @param workflowRun The run to analyze
     * @return Map containing node names as key and the log text for all steps executed on that very node as value
     * @throws java.io.IOException Will be thrown in case there something went wrong while reading the log
     */
    private Map<String, String> mapNodeNameToLogText(WorkflowRun workflowRun) throws java.io.IOException{
        FlowGraphWalker walker = new FlowGraphWalker(workflowRun.getExecution());
        Map<String, StringWriter> workspaceActionToLogText = new HashMap<>();
        for (FlowNode n : walker) {
            if (n instanceof StepAtomNode atomNode) {
              if (atomNode.getDescriptor() instanceof EchoStep.DescriptorImpl) {
                    // we're searching for the echo only...
                    LogAction l = atomNode.getAction(LogAction.class);
                    if (l != null) {
                        // Only store the log if there was no workspace action involved... (e.g. from echo)
                        List<? extends BlockStartNode> enclosingBlocks = atomNode.getEnclosingBlocks();
                        for (BlockStartNode startNode : enclosingBlocks) {
                            WorkspaceAction a = startNode.getAction(WorkspaceAction.class);
                            if (a != null) {
                                String nodeName = a.getNode();
                                if (!workspaceActionToLogText.containsKey(nodeName)) {
                                    workspaceActionToLogText.put(nodeName, new StringWriter());
                                }
                                StringWriter writer = workspaceActionToLogText.get(nodeName);
                                l.getLogText().writeLogTo(0, writer);
                            }
                        }
                    }
                }
            }
        }
        return workspaceActionToLogText.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
    }


    @Issue("JENKINS-36547")
    @Test
    void reuseNodesWithDifferentLabelsFromPreviousRuns() throws Throwable {
        sessions.then(r -> {
            createNOnlineAgentWithLabels(r, 1, "foo bar");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
            p.setDefinition(new CpsFlowDefinition(
                """
                    node('foo') {
                       echo "ran node block foo"
                    }
                    node('bar') {
                    	echo "ran node block bar"
                    }
                    """, true));
            WorkflowRun run1 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping1 = mapNodeNameToLogText(run1);

            WorkflowRun run2 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping2 = mapNodeNameToLogText(run2);

            assertEquals(nodeMapping1, nodeMapping2);
        });
    }

    /**
     * Please note that any change to the node allocation algorithm may need an increase or decrease
     * of the number of agents in order to get a pass
     */
    @Issue("JENKINS-36547")
    @Test
    void reuseNodesWithSameLabelsInDifferentReorderedStages() throws Throwable {
        sessions.then(r -> {
            // Note: for Jenkins versions > 2.265, the number of agents must be 5.
            // Older Jenkins versions used 3 agents.
            // This is due to changes in the Load Balancer (See JENKINS-60563).
            int totalAgents = 5;
            createNOnlineAgentWithLabels(r, totalAgents, "foo bar");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
            p.setDefinition(new CpsFlowDefinition("""
                stage('1') {
                   node('foo') {
                       echo "ran node block first"
                   }
                }
                stage('2') {
                   node('foo') {
                	    echo "ran node block second"
                   }
                }
                """, true));
            WorkflowRun run1 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping1 = mapNodeNameToLogText(run1);
            // if nodeMapping contains only one entry this test actually will not test anything reasonable
            // possibly the number of agents has to be adjusted in that case
            assertEquals(2, nodeMapping1.size());

            p.setDefinition(new CpsFlowDefinition("""
                stage('2') {
                   node('foo') {
                       echo "ran node block second"
                   }
                }
                stage('1') {
                   node('foo') {
                	    echo "ran node block first"
                   }
                }
                """, true));
            WorkflowRun run2 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping2 = mapNodeNameToLogText(run2);

            assertEquals(nodeMapping1, nodeMapping2);
        });
    }

    /**
     * Ensure node reuse works from within parallel block without using stages
     * Please note that any change to the node allocation algorithm may need an increase or decrease
     * of the number of agents in order to get a pass
     */
    @Issue("JENKINS-36547")
    @Test
    void reuseNodesWithSameLabelsInParallelStages() throws Throwable {
        sessions.then(r -> {
            createNOnlineAgentWithLabels(r, 4, "foo bar");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");

            // 1: the second branch shall request the node first and wait inside the node block for the
            // first branch to acquire the node
            p.setDefinition(new CpsFlowDefinition("""
                def secondBranchReady = false
                def firstBranchDone = false
                parallel(1: {
                   waitUntil { secondBranchReady }
                   node('foo') {
                       echo "ran node block first"
                   }
                   firstBranchDone = true
                }, 2: {
                   node('foo') {
                	    echo "ran node block second"
                       secondBranchReady = true
                       waitUntil { firstBranchDone }
                   }
                })
                """, true));
            WorkflowRun run1 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping1 = mapNodeNameToLogText(run1);

            // if nodeMapping contains only one entry this test actually will not test anything reasonable
            // possibly the number of agents has to be adjusted in that case
            assertEquals(2, nodeMapping1.size());

            // 2: update script to force reversed order for node blocks; shall still pick the same nodes
            p.setDefinition(new CpsFlowDefinition("""
                def firstBranchReady = false
                def secondBranchDone = false
                parallel(1: {
                   node('foo') {
                       echo "ran node block first"
                       firstBranchReady = true
                       waitUntil { secondBranchDone }
                   }
                }, 2: {
                   waitUntil { firstBranchReady }
                   node('foo') {
                	    echo "ran node block second"
                   }
                   secondBranchDone = true
                })
                """, true));
            WorkflowRun run2 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping2 = mapNodeNameToLogText(run2);
            assertEquals(nodeMapping1, nodeMapping2);
        });
    }

    /**
     * Ensure node reuse works from within parallel blocks which use the same stage names
     * Please note that any change to the node allocation algorithm may need an increase or decrease
     * of the number of agents in order to get a pass
     */
    @Issue("JENKINS-36547")
    @Test
    void reuseNodesWithSameLabelsInStagesWrappedInsideParallelStages() throws Throwable {
        sessions.then(r -> {
            createNOnlineAgentWithLabels(r, 4, "foo bar");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
            p.setDefinition(new CpsFlowDefinition("""
                def secondBranchReady = false
                def firstBranchDone = false
                parallel(1: {
                   waitUntil { secondBranchReady }
                   stage('stage1') {
                       node('foo') {
                           echo "ran node block first"
                        }
                   }
                   firstBranchDone = true
                }, 2: {
                   stage('stage1') {
                       node('foo') {
                	        echo "ran node block second"
                           secondBranchReady = true
                           waitUntil { firstBranchDone }
                        }
                   }
                })
                """, true));
            WorkflowRun run1 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping1 = mapNodeNameToLogText(run1);

            // if nodeMapping contains only one entry this test actually will not test anything reasonable
            // possibly the number of agents has to be adjusted in that case
            assertEquals(2, nodeMapping1.size());

            // update script to force reversed order for node blocks; shall still pick the same nodes
            p.setDefinition(new CpsFlowDefinition("""
                def firstBranchReady = false
                def secondBranchDone = false
                parallel(1: {
                   stage('stage1') {
                       node('foo') {
                           echo "ran node block first"
                           firstBranchReady = true
                           waitUntil { secondBranchDone }
                       }
                   }
                }, 2: {
                   waitUntil { firstBranchReady }
                   stage('stage1') {
                       node('foo') {
                    	    echo "ran node block second"
                       }
                   }
                   secondBranchDone = true
                })
                """, true));

            WorkflowRun run2 = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping2 = mapNodeNameToLogText(run2);
            assertEquals(nodeMapping1, nodeMapping2);
        });
    }

    @Issue("JENKINS-36547")
    @Test
    void reuseNodeInSameRun() throws Throwable {
        sessions.then(r -> {
            createNOnlineAgentWithLabels(r, 5, "foo");

            WorkflowJob p = r.createProject(WorkflowJob.class, "demo");
            p.setDefinition(new CpsFlowDefinition("for (int i = 0; i < 20; ++i) {node('foo') {echo \"ran node block ${i}\"}}", true));
            WorkflowRun run = r.buildAndAssertSuccess(p);
            Map<String, String> nodeMapping = mapNodeNameToLogText(run);

            // if the node was reused every time we'll only have one node mapping entry
            assertEquals(1, nodeMapping.size());
        });
    }

    @Issue("JENKINS-26132")
    @Test
    void taskDisplayName() throws Throwable {
        sessions.then(r -> {
                WorkflowJob p = r.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    """
                        stage('one') {
                          node {
                            semaphore 'one'
                            stage('two') {
                              semaphore 'two'
                            }
                          }
                        }
                        stage('three') {
                          node {
                            semaphore 'three'
                          }
                          parallel a: {
                            node {
                              semaphore 'a'
                            }
                          }, b: {
                            node {
                              semaphore 'b'
                            }
                          }
                        }""", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("one/1", b);
                assertEquals(Collections.singletonList(n(b, "one")), currentLabels(r));
                assertEquals(Collections.singletonList(n(b, "one")), currentLabels(r));
                SemaphoreStep.success("one/1", null);
                SemaphoreStep.waitForStart("two/1", b);
                assertEquals(Collections.singletonList(n(b, "two")), currentLabels(r));
                SemaphoreStep.success("two/1", null);
                SemaphoreStep.waitForStart("three/1", b);
                assertEquals(Collections.singletonList(n(b, "three")), currentLabels(r));
                SemaphoreStep.success("three/1", null);
                SemaphoreStep.waitForStart("a/1", b);
                SemaphoreStep.waitForStart("b/1", b);
                assertEquals(Arrays.asList(n(b, "a"), n(b, "b")), currentLabels(r));
                SemaphoreStep.success("a/1", null);
                SemaphoreStep.success("b/1", null);
                r.waitForCompletion(b);
        });
    }

    private static String n(Run<?, ?> b, String label) {
        return Messages.ExecutorStepExecution_PlaceholderTask_displayName_label(b.getFullDisplayName(), label);
    }

    private static List<String> currentLabels(JenkinsRule r) {
        List<String> result = new ArrayList<>();
        for (Executor executor : r.jenkins.toComputer().getExecutors()) {
            Queue.Executable executable = executor.getCurrentExecutable();
            if (executable != null) {
                result.add(executable.getParent().getDisplayName());
            }
        }
        Collections.sort(result);
        return result;
    }


    @Issue("SECURITY-675")
    @Test
    void authentication() throws Throwable {
        sessions.then(r -> {
            logging.record(ExecutorStepExecution.class, Level.FINE);
            Slave s = r.createSlave("remote", null, null);
            r.waitOnline(s);
            r.jenkins.setNumExecutors(0);
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                grant(Jenkins.ADMINISTER).everywhere().to("admin"));
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            // First check that if the build is run as dev, they are not allowed to use this agent:
            QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MainAuthenticator());
            p.setDefinition(new CpsFlowDefinition("timeout(time: 5, unit: 'SECONDS') {node {error 'should not be allowed'}}", true));
            r.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0));
            assertThat(Queue.getInstance().getItems(), emptyArray());
            // What about when there is a fallback authenticator?
            QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new FallbackAuthenticator());
            r.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0));
            // Must also work even if the PlaceholderTask is recreated:
            p.setDefinition(new CpsFlowDefinition("node {error 'should not be allowed'}", true));
            s.toComputer().setTemporarilyOffline(true, null);
            r.waitForMessage("Still waiting to schedule task", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            r.waitOnline((Slave) r.jenkins.getNode("remote"));
            Thread.sleep(5000);
            WorkflowRun b3 = p.getBuildByNumber(3);
            assertTrue(b3.isBuilding());
            b3.doStop();
        });
    }

    @Issue("JENKINS-58900")
    @Test
    void nodeDisconnectMissingContextVariableException() throws Throwable {
        sessions.then(r -> {
            DumbSlave agent = r.createSlave();
            WorkflowJob p = r.createProject(WorkflowJob.class);
            p.setDefinition(new CpsFlowDefinition(
                    "node ('" + agent.getNodeName() + "') {\n" +
                    "  def isUnix = isUnix()\n" + // Only call `isUnix()` before the agent goes offline to avoid additional log warnings.
                    "  isUnix ? sh('echo hello') : bat('echo hello')\n" +
                    "  semaphore('wait')\n" +
                    "  isUnix ? sh('echo world') : bat('echo world')\n" +
                    "}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            agent.toComputer().disconnect(new OfflineCause.UserCause(User.getUnknown(), "going offline"));
            while (agent.toComputer().isOnline()) {
                Thread.sleep(100);
            }
            SemaphoreStep.success("wait/1", null);
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.FAILURE, b);
            r.assertLogContains("hello", b);
            r.assertLogNotContains("world", b);
            r.assertLogContains("going offline", b);
            r.assertLogContains("AgentOfflineException: Unable to create live FilePath for " + agent.getNodeName(), b);
        });
    }

    @Test
    @Issue("JENKINS-60634") void tempDirVariable() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node {if (isUnix()) {sh 'set -u && touch \"$WORKSPACE_TMP/x\"'} else {bat(/echo ok > \"%WORKSPACE_TMP%\\x\"/)}}", true));
            r.buildAndAssertSuccess(p);
            assertTrue(WorkspaceList.tempDir(r.jenkins.getWorkspaceFor(p)).child("x").exists());
        });
    }

    @Test
    @Issue("JENKINS-63486") void getOwnerTaskPermissions() throws Throwable {
        sessions.then(r -> {
            MockFolder f = r.createFolder("f");
            WorkflowJob p = f.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node() { semaphore('wait') }", true));

            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                    .grant(Job.DISCOVER).onFolders(f).toEveryone()
                    .grant(Job.READ).onItems(p).toEveryone());
            User alice = User.get("alice", true, Collections.emptyMap());

            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            for (Executor e : Jenkins.get().toComputer().getExecutors()) {
                try (ACLContext context = ACL.as(alice)) {
                    e.hasStopPermission(); // Throws AccessDeniedException before JENKINS-63486.
                }
            }
        });
    }

    @Test
    void getParentExecutable() throws Throwable {
        sessions.then(r -> {
            DumbSlave s = r.createSlave();
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('" + s.getNodeName() + "') {semaphore('wait')}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            List<Executor> executors = s.toComputer().getExecutors();
            assertEquals(1, executors.size());
            Queue.Executable exec = executors.get(0).getCurrentExecutable();
            assertNotNull(exec);
            assertEquals(b, exec.getParentExecutable());
            SemaphoreStep.success("wait/1", null);
        });
    }

    @Test
    void placeholderTaskInQueueButAssociatedBuildComplete() throws Throwable {
        logging.record(ExecutorStepExecution.class, Level.FINE).capture(50);
        Path tempQueueFile = File.createTempFile("junit", null, tmp).toPath();
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('custom-label') { }", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            // Get into a state where a PlaceholderTask is in the queue.
            while (true) {
                Queue.Item[] items = Queue.getInstance().getItems();
                if (items.length == 1 && items[0].task instanceof ExecutorStepExecution.PlaceholderTask) {
                    break;
                }
                Thread.sleep(500L);
            }
            // Copy queue.xml to a temp file while the PlaceholderTask is in the queue.
            r.jenkins.getQueue().save();
            Files.copy(sessions.getHome().toPath().resolve("queue.xml"), tempQueueFile, StandardCopyOption.REPLACE_EXISTING);
            // Create a node with the correct label and let the build complete.
            DumbSlave node = r.createOnlineSlave(Label.get("custom-label"));
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            // Remove node so that tasks requiring custom-label are stuck in the queue.
            Jenkins.get().removeNode(node);
        });
        // Copy the temp queue.xml over the real one. The associated build has already completed, so the queue now
        // has a bogus PlaceholderTask.
        Files.copy(tempQueueFile, sessions.getHome().toPath().resolve("queue.xml"), StandardCopyOption.REPLACE_EXISTING);
        sessions.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            assertFalse(b.isLogUpdated());
            r.assertBuildStatusSuccess(b);
            Queue.getInstance().maintain(); // Otherwise we may have to wait up to 5 seconds.
            while (Queue.getInstance().getItems().length > 0) {
                Thread.sleep(100L);
            }
            assertThat(logging.getMessages(), hasItem(startsWith("Refusing to build ExecutorStepExecution.PlaceholderTask")));
        });
    }

    @Test
    void restartWhilePlaceholderTaskIsInQueue() throws Throwable {
        // Node reconnection takes a while during the second restart.
        System.setProperty("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis", String.valueOf(TimeUnit.SECONDS.toMillis(30)));
        logging.record(ExecutorStepExecution.class, Level.FINE)
                .record(ExecutorStepDynamicContext.class, Level.FINE)
                .capture(100);
        sessions.then(r -> {
            inboundAgents.createAgent(r, "custom-label");
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node('custom-label') { semaphore('wait') }", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            Slave node = (Slave) r.jenkins.getNode("custom-label");
            node.setNumExecutors(0); // Make sure the step won't be able to resume.
        });
        sessions.then(r ->
            // Just wait for ExecutorStepDynamicContext.resume to schedule PlaceholderTask and then restart.
            await().atMost(15, TimeUnit.SECONDS).until(
                    () -> Stream.of(Queue.getInstance().getItems()).map(item -> item.task).collect(Collectors.toList()),
                    hasItem(instanceOf(ExecutorStepExecution.PlaceholderTask.class))));
        sessions.then(r -> {
            ((Slave) r.jenkins.getNode("custom-label")).setNumExecutors(1); // Allow node step to resume.
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            SemaphoreStep.success("wait/1", null);
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            assertThat(r.jenkins.getQueue().getItems(), emptyArray());
            await().until(() -> Stream.of(r.jenkins.getComputers())
                    .flatMap(c -> c.getExecutors().stream())
                    .filter(e -> e.getCurrentWorkUnit() != null)
                    .collect(Collectors.toList()), empty());
            inboundAgents.stop(r, "custom-label");
        });
    }

    private static class MainAuthenticator extends QueueItemAuthenticator {
        @Override public Authentication authenticate2(Queue.Task task) {
            return task instanceof WorkflowJob ? User.getById("dev", true).impersonate2() : null;
        }
    }
    private static class FallbackAuthenticator extends QueueItemAuthenticator {
        @Override public Authentication authenticate2(Queue.Task task) {
            return ACL.SYSTEM2;
        }
    }

    private void createNOnlineAgentWithLabels(JenkinsRule r, int number, String label) throws Exception {
        // create all the slaves then wait for them to connect as it will be quicker as agents connect in parallel
        ArrayList<DumbSlave> agents = new ArrayList<>();
        for (int i = 0; i < number; ++i) {
            agents.add(r.createSlave(label, null));
        }
        for (DumbSlave agent : agents) {
            r.waitOnline(agent);
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
