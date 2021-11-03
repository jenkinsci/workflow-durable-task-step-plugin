/*
 * The MIT License
 *
 * Copyright (c) 2015 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow;

import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Verifies that specific environment variables are available.
 *
 */
public class EnvWorkflowTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    /**
     * Verifies if NODE_NAME environment variable is available on a slave node and on master.
     */
    @Test public void isNodeNameAvailable() throws Exception {
        r.createSlave("node-test", "unix fast", null);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getExpression(); // compatibility with 2.307+
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "workflow-test");
        final String builtInNodeName = r.jenkins.getSelfLabel().getName();

        p.setDefinition(new CpsFlowDefinition(
            "node('" + builtInNodeLabel + "') {\n" +
            "  echo \"My name on master is ${env.NODE_NAME} using labels ${env.NODE_LABELS}\"\n" +
            "}\n",
            true));
        r.assertLogContains("My name on master is " + builtInNodeName + " using labels " + builtInNodeLabel, r.assertBuildStatusSuccess(p.scheduleBuild2(0)));

        p.setDefinition(new CpsFlowDefinition(
            "node('node-test') {\n" +
            "  echo \"My name on a slave is ${env.NODE_NAME} using labels ${env.NODE_LABELS}\"\n" +
            "}\n",
            true));
        // Label.parse returns TreeSet so the result is guaranteed to be sorted:
        r.assertLogContains("My name on a slave is node-test using labels fast node-test unix", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));

        p.setDefinition(new CpsFlowDefinition( // JENKINS-41446 ensure variable still available in a ws step
            "node('node-test') {\n ws('workspace/foo') {" +
            "    echo \"My name on a slave is ${env.NODE_NAME} using labels ${env.NODE_LABELS}\"\n" +
            "  }\n}\n",
            true));
        // Label.parse returns TreeSet so the result is guaranteed to be sorted:
        r.assertLogContains("My name on a slave is node-test using labels fast node-test unix", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }


    /**
     * Verifies if EXECUTOR_NUMBER environment variable is available on a slave node and on master.
     */
    @Test public void isExecutorNumberAvailable() throws Exception {
        r.jenkins.setNumExecutors(1);
        r.createSlave("node-test", null, null);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getExpression(); // compatibility with 2.307+
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "workflow-test");

        p.setDefinition(new CpsFlowDefinition(
                "node('" + builtInNodeLabel + "') {\n" +
                        "  echo \"My number on master is ${env.EXECUTOR_NUMBER}\"\n" +
                        "}\n",
                true));
        r.assertLogContains("My number on master is 0", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));

        p.setDefinition(new CpsFlowDefinition(
                "node('node-test') {\n" +
                        "  echo \"My number on a slave is ${env.EXECUTOR_NUMBER}\"\n" +
                        "}\n",
                true));
        r.assertLogContains("My number on a slave is 0", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-33511")
    @Test public void isWorkspaceAvailable() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        String builtInNodeLabel = r.jenkins.getSelfLabel().getExpression(); // compatibility with 2.307+
        p.setDefinition(new CpsFlowDefinition("node('" + builtInNodeLabel + "') {echo(/running in ${env.WORKSPACE}/)}", true));
        r.assertLogContains("running in " + r.jenkins.getWorkspaceFor(p), r.assertBuildStatusSuccess(p.scheduleBuild2(0)));
        DumbSlave remote = r.createSlave("remote", null, null);
        p.setDefinition(new CpsFlowDefinition("node('remote') {echo(/running in ${env.WORKSPACE}/)}", true));
        WorkflowRun b2 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("running in " + remote.getWorkspaceFor(p), b2);
        p.setDefinition(new CpsFlowDefinition("node('remote') {ws('workspace/foo') {echo(/running in ${env.WORKSPACE}/)}}", true)); // JENKINS-41446
        WorkflowRun b3 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("running in " + remote.getRootPath().child("workspace/foo"), b3);
    }

}
