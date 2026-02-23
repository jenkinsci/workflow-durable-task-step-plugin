/*
 * The MIT License
 *
 * Copyright (c) 2024, CloudBees, Inc.
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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepTypePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.InboundAgentRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TailLog;

public class ExecutorStepExecutionRJRTest {
    private static final Logger LOGGER = Logger.getLogger(ExecutorStepExecutionRJRTest.class.getName());

    @Rule public RealJenkinsRule rjr = new RealJenkinsRule().withColor(PrefixedOutputStream.Color.GREEN).withPackageLogger(ExecutorStepExecution.class, Level.FINE).withPackageLogger(FlowExecutionList.class, Level.FINE);

    @Rule
    public InboundAgentRule iar = new InboundAgentRule();

    @Test public void restartWhileWaitingForANode() throws Throwable {
        rjr.startJenkins();
        try (var tailLog = new TailLog(rjr, "p", 1)) {
            iar.createAgent(rjr, InboundAgentRule.Options.newBuilder().name("J").label("mib").color(PrefixedOutputStream.Color.YELLOW).webSocket().build());
            rjr.runRemotely(ExecutorStepExecutionRJRTest::setupJobAndStart);
            rjr.stopJenkinsForcibly();
            rjr.startJenkins();
            rjr.runRemotely(ExecutorStepExecutionRJRTest::resumeCompleteBranch1ThenBranch2);
        }
    }

    private static void resumeCompleteBranch1ThenBranch2(JenkinsRule r) throws Throwable {
        var p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        var b = p.getBuildByNumber(1);
        await("Waiting for agent J to reconnect").atMost(Duration.ofSeconds(30)).until(() -> r.jenkins.getComputer("J").isOnline());
        var actions = await().until(() -> b.getActions(InputAction.class), allOf(iterableWithSize(1), hasItem(new InputActionWithId("Branch1"))));
        proceed(actions, "Branch1", p.getName() + "#" + b.number);
        // This is quicker than waitForMessage that can wait for up to 10 minutes
        actions = await().until(() -> b.getActions(InputAction.class), allOf(iterableWithSize(1), hasItem(new InputActionWithId("Branch2"))));
        r.waitForMessage("Complete branch 2 ?", b);
        proceed(actions, "Branch2", p.getName() + "#" + b.number);
        r.waitForCompletion(b);
    }

    private static class InputActionWithId extends TypeSafeMatcher<InputAction> {
        private final String inputId;

        private InputActionWithId(String inputId) {
            this.inputId = inputId;
        }


        @Override
        protected boolean matchesSafely(InputAction inputAction) {
            try {
                return inputAction.getExecutions().stream().anyMatch(execution -> inputId.equals(execution.getId()));
            } catch (InterruptedException | TimeoutException e) {
                return false;
            }
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("has input with id ").appendValue(inputId);
        }
    }

    private static void proceed(List<InputAction> actions, String inputId, String name) throws InterruptedException, TimeoutException, IOException {
        for (var action : actions) {
            if (action.getExecutions().isEmpty()) {
                continue;
            }
            var inputStepExecution = action.getExecutions().get(0);
            if (inputId.equals(inputStepExecution.getId())) {
                LOGGER.info(() -> "proceeding " + name);
                inputStepExecution.proceed(null);
                break;
            }
        }
    }

    private static void setupJobAndStart(JenkinsRule r) throws Exception {
        var p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                parallel 'Branch 1': {
                    node('mib') {
                        input id: 'Branch1', message: 'Complete branch 1 ?'
                    }
                }, 'Branch 2': {
                    sleep 1
                    node('mib') {
                        input id:'Branch2', message: 'Complete branch 2 ?'
                    }
                }
                """, true));
        var b = p.scheduleBuild2(0).waitForStart();
        r.waitForMessage("Complete branch 1 ?", b);
        assertTrue(b.isBuilding());
        await().until(() -> r.jenkins.getQueue().getItems(), arrayWithSize(1));
        LOGGER.info("Node steps: " + new DepthFirstScanner().filteredNodes(b.getExecution(), new NodeStepTypePredicate("node")));
        // "Branch 1" step start + "Branch 1" body start + "Branch 2" step start
        await().until(() -> new DepthFirstScanner().filteredNodes(b.getExecution(), new NodeStepTypePredicate("node")), hasSize(3));
    }
}
