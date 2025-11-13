/*
 * The MIT License
 *
 * Copyright 2025 CloudBees, Inc.
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
import hudson.slaves.DumbSlave;
import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import org.jenkinci.plugins.mock_slave.MockSlaveLauncher;
import org.jenkinsci.plugins.durabletask.FileMonitoringTask;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.steps.durable_task.exitDuringShutdownTest.FinishProcess;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.TailLog;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

class ExitDuringShutdownTest {

    @RegisterExtension
    private final RealJenkinsExtension rr = new RealJenkinsExtension().
        addSyntheticPlugin(new RealJenkinsExtension.SyntheticPlugin(FinishProcess.class).shortName("ExitDuringShutdownTest").header("Plugin-Dependencies", "workflow-cps:0")).
        javaOptions("-Dorg.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis=" + Duration.ofMinutes(5).toMillis()). // reconnection could be >15s esp. on Windows
        javaOptions("-D" + DurableTaskStep.class.getName() + ".USE_WATCHING=true").
        withColor(PrefixedOutputStream.Color.BLUE).
        withLogger(DurableTaskStep.class, Level.FINE).
        withLogger(FileMonitoringTask.class, Level.FINE);

    @Test
    void scriptExitingDuringShutdown() throws Throwable {
        assumeFalse(Functions.isWindows(), "TODO Windows version TBD");
        rr.startJenkins();
        try (var tailLog = new TailLog(rr, "p", 1).withColor(PrefixedOutputStream.Color.YELLOW)) {
            rr.run(r -> {
                var s = new DumbSlave("remote", new File(r.jenkins.getRootDir(), "agent").getAbsolutePath(), new MockSlaveLauncher(0, 0));
                r.jenkins.addNode(s);
                r.waitOnline(s);
                r.showAgentLogs(s, Map.of(DurableTaskStep.class.getPackageName(), Level.FINE, FileMonitoringTask.class.getPackageName(), Level.FINE));
                var p = r.createProject(WorkflowJob.class, "p");
                var f = new File(r.jenkins.getRootDir(), "f");
                p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("F")));
                p.setDefinition(new CpsFlowDefinition(
                    """
                    node('remote') {
                      sh 'set +x; until test -f "$F"; do :; done; echo got it'
                    }""", true));
                var b = p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("F", f.getAbsolutePath()))).waitForStart();
                r.waitForMessage("set +x", b);
            });
            rr.stopJenkins();
            var f = new File(rr.getHome(), "f");
            rr.startJenkins();
            rr.run(r -> {
                var p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
                var b = p.getLastBuild();
                r.assertBuildStatusSuccess(r.waitForCompletion(b));
            });
            tailLog.waitForCompletion();
        }
    }

}
