/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assume;
import org.jvnet.hudson.test.JenkinsRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import hudson.Functions;

import java.nio.charset.StandardCharsets;

public class PowerShellStepTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    // Ensure that powershell exists and is at least version 3
    @Before public void ensurePowerShellSufficientVersion() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "pretest");
        p.setDefinition(new CpsFlowDefinition(Functions.isWindows() ?
        "node { bat('powershell.exe -ExecutionPolicy Bypass -NonInteractive -Command \"if ($PSVersionTable.PSVersion.Major -ge 3) {exit 0} else {exit 1}\"') }" :
        "node { sh('powershell -NonInteractive -Command \"if ($PSVersionTable.PSVersion.Major -ge 3) {exit 0} else {exit 1}\"') }", true));
        WorkflowRun b = p.scheduleBuild2(0).get();
        Result r = b.getResult();
        Assume.assumeTrue("This test should only run if the pretest workflow job succeeded", r == Result.SUCCESS);
    }

    // Test that a powershell step producing particular output indeed has a log containing that output
    @Test public void testOutput() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "foo");
        p.setDefinition(new CpsFlowDefinition("node {powershell 'Write-Output \"a moon full of stars and astral cars\"'}", true));
        j.assertLogContains("a moon full of stars and astral cars", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }
    
    // Test that a powershell step that fails indeed causes the underlying build to fail
    @Test public void testFailure() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "bar");
        p.setDefinition(new CpsFlowDefinition("node {powershell 'throw \"bogus error\"'}", true));
        j.assertLogContains("bogus error", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
    }
    
    @Test public void testUnicode() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "foobar");
        p.setDefinition(new CpsFlowDefinition("node {def x = powershell(returnStdout: true, script: 'write-output \"Hëllö Wórld\"'); println x.replace(\"\ufeff\",\"\")}", true));
        String log = new String(JenkinsRule.getLog(j.assertBuildStatusSuccess(p.scheduleBuild2(0))).getBytes(), StandardCharsets.UTF_8);
        Assume.assumeTrue("Correct UTF-8 output should be produced",log.contains("Hëllö Wórld"));
    }

}

