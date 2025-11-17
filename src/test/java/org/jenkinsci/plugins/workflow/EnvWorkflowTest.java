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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.is;

import hudson.slaves.DumbSlave;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that specific environment variables are available.
 *
 */
@WithJenkins
class EnvWorkflowTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }
    /**
     * Verifies if NODE_NAME environment variable is available on an agent node and on the built-in node.
     */
    @Test
    void isNodeNameAvailable() throws Exception {
        r.createSlave("node-test", "unix fast", null);
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "workflow-test");

        p.setDefinition(new CpsFlowDefinition(
            """
            node('built-in') {
              echo "My name on the built-in node is ${env.NODE_NAME} using labels ${env.NODE_LABELS}"
            }
            """,
            true));
        r.assertLogContains("My name on the built-in node is built-in using labels built-in", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));

        p.setDefinition(new CpsFlowDefinition(
            """
            node('node-test') {
              echo "My name on an agent is ${env.NODE_NAME} using labels ${env.NODE_LABELS}"
            }
            """,
            true));
        matchLabelsInAnyOrder(
                r.assertBuildStatusSuccess(p.scheduleBuild2(0)),
                "My name on an agent is node-test using labels (.*)",
                "fast",
                "node-test",
                "unix");

        // JENKINS-41446 ensure variable still available in a ws step
        p.setDefinition(new CpsFlowDefinition(
        """
        node('node-test') {
         ws('workspace/foo') {    echo "My name on an agent is ${env.NODE_NAME} using labels ${env.NODE_LABELS}"
          }
        }
        """,
            true));
        matchLabelsInAnyOrder(
                r.assertBuildStatusSuccess(p.scheduleBuild2(0)),
                "My name on an agent is node-test using labels (.*)",
                "fast",
                "node-test",
                "unix");
    }

    private void matchLabelsInAnyOrder(WorkflowRun run, String regex, String... labels) throws Exception {
        String runLog = JenkinsRule.getLog(run);
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(runLog);
        assertThat("Could not match the expected log pattern", matcher.find(), is(true));
        assertThat(
                "Did not find the expected labels",
                matcher.group(1).split("\\s+"),
                arrayContainingInAnyOrder(labels));
    }


    /**
     * Verifies if EXECUTOR_NUMBER environment variable is available on an agent node and on the built-in node.
     */
    @Test
    void isExecutorNumberAvailable() throws Exception {
        r.jenkins.setNumExecutors(1);
        r.createSlave("node-test", null, null);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getExpression(); // compatibility with 2.307+
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "workflow-test");

        p.setDefinition(new CpsFlowDefinition(
                "node('" + builtInNodeLabel + "') {\n" +
                        "  echo \"Executor number on built-in node is ${env.EXECUTOR_NUMBER}\"\n" +
                        "}\n",
                true));
        r.assertLogContains("Executor number on built-in node is 0", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));

        p.setDefinition(new CpsFlowDefinition(
            """
                node('node-test') {
                  echo "My number on an agent is ${env.EXECUTOR_NUMBER}"
                }
                """,
                true));
        r.assertLogContains("My number on an agent is 0", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-33511")
    @Test
    void isWorkspaceAvailable() throws Exception {
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
