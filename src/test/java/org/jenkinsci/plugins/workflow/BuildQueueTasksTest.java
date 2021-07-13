/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.xml.sax.SAXException;

import com.gargoylesoftware.htmlunit.Page;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class BuildQueueTasksTest {

    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @Issue("JENKINS-28649")
    @Test public void queueAPI() throws Throwable {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask as exported bean
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                // use non-existent node label to keep the build queued
                p.setDefinition(new CpsFlowDefinition("node('nonexistent') { echo 'test' }", true));

                WorkflowRun b = scheduleAndWaitQueued(p, j);
                assertQueueAPIStatusOKAndAbort(b, j);
        });
    }

    @Issue("JENKINS-28649")
    @Test public void queueAPIRestartable() throws Throwable {
        // This is implicitly testing AfterRestartTask as exported bean
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                // use non-existent node label to keep the build queued
                p.setDefinition(new CpsFlowDefinition("node('nonexistent') { echo 'test' }", true));
                scheduleAndWaitQueued(p, j);
                // Ok, the item is in he queue now, restart
        });
        sessions.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);

                assertQueueAPIStatusOKAndAbort(b, j);
        });
    }

    @Issue("JENKINS-28649")
    @Test public void computerAPI() throws Throwable {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask$PlaceholderExecutable as exported bean
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "node {\n" +
                        "  echo 'test'\n " +
                        "  semaphore 'watch'\n " +
                        "}", true));

                WorkflowRun b = p.scheduleBuild2(0).getStartCondition().get();
                SemaphoreStep.waitForStart("watch/1", b);

                assertComputerAPIStatusOK(j);

                SemaphoreStep.success("watch/1", null);
        });
    }

    private static WorkflowRun scheduleAndWaitQueued(WorkflowJob p, JenkinsRule j) throws InterruptedException, ExecutionException {
        QueueTaskFuture<WorkflowRun> build = p.scheduleBuild2(0);

        WorkflowRun b = build.getStartCondition().get();
        int secondsWaiting = 0;
        while (true) {
            if (secondsWaiting > 15) {
                fail("No item queued after 15 seconds");
            }
            if (j.jenkins.getQueue().getItems().length > 0) {
                break;
            }
            Thread.sleep(1000);
            secondsWaiting++;
        }
        return b;
    }

    private static void assertQueueAPIStatusOKAndAbort(WorkflowRun b, JenkinsRule j)
            throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        Page queue = wc.goTo("queue/api/json", "application/json");

        JSONObject o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        JSONArray items = o.getJSONArray("items");
        // Just check that the request returns HTTP 200 and there is some content. 
        // Not going into de the content in this test
        assertEquals(1, items.size());

        b.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));

        queue = wc.goTo("queue/api/json", "application/json");
        o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        items = o.getJSONArray("items");

        assertEquals(0, items.size());
    }

    private static void assertComputerAPIStatusOK(JenkinsRule j) throws IOException, SAXException {
        JenkinsRule.WebClient wc = j.createWebClient();
        Page queue = wc.goTo("computer/api/json?tree=computer[executors[*]]", "application/json");

        JSONObject o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        JSONArray computers = o.getJSONArray("computer");
        // Just check that the request returns HTTP 200 and there is some content.
        // Not going into de the content in this test
        assertEquals(1, computers.size());
    }

}
