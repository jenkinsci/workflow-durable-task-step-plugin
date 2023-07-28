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

import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerListener;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.slaves.restarter.JnlpSlaveRestarterInstaller;
import static org.awaitility.Awaitility.await;
import org.jenkinsci.plugins.durabletask.FileMonitoringTask;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.log.FileLogStorage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.InboundAgentRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TailLog;

@RunWith(Parameterized.class)
public final class DurableTaskStepTest {

    @Parameterized.Parameters(name = "watching={0}") public static List<Boolean> data() {
        return List.of(false, true);
    }

    @Parameterized.Parameter public boolean useWatching;

    private static final Logger LOGGER = Logger.getLogger(DurableTaskStepTest.class.getName());

    @Rule public RealJenkinsRule rr = new RealJenkinsRule().
        javaOptions("-Dorg.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis=" + Duration.ofMinutes(5).toMillis()). // reconnection could be >15s esp. on Windows
        withColor(PrefixedOutputStream.Color.BLUE).
        withLogger(DurableTaskStep.class, Level.FINE).
        withLogger(FileMonitoringTask.class, Level.FINE);

    @Rule public InboundAgentRule inboundAgents = new InboundAgentRule();

    @Test public void scriptExitingAcrossRestart() throws Throwable {
        rr.javaOptions("-D" + DurableTaskStep.class.getName() + ".USE_WATCHING=" + useWatching);
        rr.startJenkins();
        rr.runRemotely(DurableTaskStepTest::disableJnlpSlaveRestarterInstaller);
        inboundAgents.createAgent(rr, InboundAgentRule.Options.newBuilder().
            color(PrefixedOutputStream.Color.MAGENTA).
            label("remote").
            withLogger(FileMonitoringTask.class, Level.FINER).
            withLogger(DurableTaskStep.class, Level.FINEST).
            withPackageLogger(FileLogStorage.class, Level.FINE).
            build());
        try (var tailLog = new TailLog(rr, "p", 1).withColor(PrefixedOutputStream.Color.YELLOW)) {
            rr.runRemotely(DurableTaskStepTest::scriptExitingAcrossRestart1);
            rr.stopJenkins();
            var f = new File(rr.getHome(), "f");
            LOGGER.info(() -> "Waiting for " + f + " to be written…");
            await().until(f::isFile);
            LOGGER.info("…done.");
            rr.startJenkins();
            rr.runRemotely(DurableTaskStepTest::scriptExitingAcrossRestart2);
            tailLog.waitForCompletion();
        }
    }

    /**
     * Simulate {@link AbstractCloudSlave} as in https://github.com/jenkinsci/jenkins/pull/7693.
     * Most users should be using cloud agents,
     * and this lets us preserve {@link InboundAgentRule.Options.Builder#withLogger(Class, Level)}.
     */
    private static void disableJnlpSlaveRestarterInstaller(JenkinsRule r) throws Throwable {
        ComputerListener.all().remove(ExtensionList.lookupSingleton(JnlpSlaveRestarterInstaller.class));
    }

    private static void scriptExitingAcrossRestart1(JenkinsRule r) throws Throwable {
        var p = r.createProject(WorkflowJob.class, "p");
        var f = new File(r.jenkins.getRootDir(), "f");
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("F")));
        p.setDefinition(new CpsFlowDefinition("node('remote') {if (isUnix()) {sh 'sleep 5 && touch \"$F\"'} else {bat 'ping -n 5 localhost && copy nul \"%F%\" && dir \"%F%\"'}}", true));
        var b = p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("F", f.getAbsolutePath()))).waitForStart();
        r.waitForMessage(Functions.isWindows() ? ">ping -n 5 localhost" : "+ sleep 5", b);
        r.jenkins.doQuietDown(true, 0, null);
    }

    private static void scriptExitingAcrossRestart2(JenkinsRule r) throws Throwable {
        var p = (WorkflowJob) r.jenkins.getItem("p");
        var b = p.getLastBuild();
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        // In the case of Bourne shell, `+ touch …` is printed when the command actually runs.
        // In the case of batch shell, the whole command is printed immediately, so we need to assert that the _output_ of `dir` is there.
        r.assertLogContains(Functions.isWindows() ? "Directory of " + r.jenkins.getRootDir() : "+ touch " + new File(r.jenkins.getRootDir(), "f"), b);
    }

}
