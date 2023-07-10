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

package org.jenkinsci.plugins.workflow.steps.durable_task;

import hudson.Functions;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.awaitility.Awaitility.await;
import org.jenkinsci.plugins.durabletask.FileMonitoringTask;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.InboundAgentRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TailLog;

public final class RealShellStepTest {

    private static final Logger LOGGER = Logger.getLogger(RealShellStepTest.class.getName());

    @Rule public RealJenkinsRule rr = new RealJenkinsRule().
        withColor(PrefixedOutputStream.Color.BLUE).
        withLogger(DurableTaskStep.class, Level.FINE).
        withLogger(FileMonitoringTask.class, Level.FINE);

    @Rule public InboundAgentRule inboundAgents = new InboundAgentRule();

    @Test public void shellScriptExitingAcrossRestart() throws Throwable {
        Assume.assumeFalse("TODO translate to batch script", Functions.isWindows());
        rr.startJenkins();
        inboundAgents.createAgent(rr, InboundAgentRule.Options.newBuilder().color(PrefixedOutputStream.Color.MAGENTA).label("remote").build());
        try (var tailLog = new TailLog(rr, "p", 1).withColor(PrefixedOutputStream.Color.YELLOW)) {
            rr.runRemotely(RealShellStepTest::shellScriptExitingAcrossRestart1);
            rr.stopJenkins();
            var f = new File(rr.getHome(), "f");
            LOGGER.info(() -> "Waiting for " + f + " to be written…");
            await().until(f::isFile);
            LOGGER.info("…done.");
            rr.startJenkins();
            rr.runRemotely(RealShellStepTest::shellScriptExitingAcrossRestart2);
            tailLog.waitForCompletion();
        }
    }
    private static void shellScriptExitingAcrossRestart1(JenkinsRule r) throws Throwable {
        var p = r.createProject(WorkflowJob.class, "p");
        var f = new File(r.jenkins.getRootDir(), "f");
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("F")));
        p.setDefinition(new CpsFlowDefinition("node('remote') {sh 'sleep 5 && touch \"$F\"'}", true));
        var b = p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("F", f.getAbsolutePath()))).waitForStart();
        r.waitForMessage("+ sleep 5", b);
        r.jenkins.doQuietDown(true, 0, null);
    }
    private static void shellScriptExitingAcrossRestart2(JenkinsRule r) throws Throwable {
        var p = (WorkflowJob) r.jenkins.getItem("p");
        var b = p.getLastBuild();
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        /* TODO this late output is sometimes lost:
        r.assertLogContains("+ touch " + new File(r.jenkins.getRootDir(), "f"), b);
        */
    }

}
