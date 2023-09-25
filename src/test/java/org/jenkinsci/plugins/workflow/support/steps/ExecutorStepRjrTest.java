/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Slave;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.InboundAgentRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

public class ExecutorStepRjrTest {

    @Rule
    public RealJenkinsRule rjr = new RealJenkinsRule()
            .withLogger(ExecutorStepExecution.class, Level.FINE)
            .withLogger(ExecutorStepDynamicContext.class, Level.FINE)
            .javaOptions("-Dorg.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis=" + TimeUnit.SECONDS.toMillis(30));

    @Rule
    public InboundAgentRule iar = new InboundAgentRule();

    @Test
    public void restartDuringStepResumption() throws Throwable {
        try {
            rjr.startJenkins();
            iar.createAgent(rjr, "custom-label");
            rjr.runRemotely(ExecutorStepRjrTest::restartDuringStepResumption_start);
            rjr.stopJenkins();
            rjr.then(ExecutorStepRjrTest::restartDuringStepResumption_firstRestart);
            rjr.then(ExecutorStepRjrTest::restartDuringStepResumption_secondRestart);
        } finally {
            iar.stop("custom-label");
        }
    }

    private static void restartDuringStepResumption_start(JenkinsRule r) throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('custom-label') { echo 'node step started'; input('test') }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        await().atMost(15, TimeUnit.SECONDS).until(() -> b.getAction(InputAction.class), notNullValue());
        Slave node = (Slave) r.jenkins.getNode("custom-label");
        node.setNumExecutors(0); // Make sure the step won't be able to resume.
    }

    private static void restartDuringStepResumption_firstRestart(JenkinsRule r) throws Exception {
        // Just wait for ExecutorStepDynamicContext.resume to schedule PlaceholderTask and then restart.
        await().atMost(15, TimeUnit.SECONDS).until(
                () -> Stream.of(Queue.getInstance().getItems()).map(item -> item.task).collect(Collectors.toList()),
                hasItem(instanceOf(ExecutorStepExecution.PlaceholderTask.class)));
    }

    private static void restartDuringStepResumption_secondRestart(JenkinsRule r) throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        WorkflowRun b = p.getBuildByNumber(1);
        ((Slave) r.jenkins.getNode("custom-label")).setNumExecutors(1); // Allow node step to resume.
        for (InputStepExecution e : b.getAction(InputAction.class).getExecutions()) {
            e.doProceedEmpty();
        }
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        assertThat(r.jenkins.getQueue().getItems(), emptyArray());
        List<Executor> occupiedExecutors = Stream.of(r.jenkins.getComputers())
                .flatMap(c -> c.getExecutors().stream())
                .filter(e -> e.getCurrentWorkUnit() != null)
                .collect(Collectors.toList());
        assertThat(occupiedExecutors, empty());
    }
}
