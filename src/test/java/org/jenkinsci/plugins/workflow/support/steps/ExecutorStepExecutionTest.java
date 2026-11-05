package org.jenkinsci.plugins.workflow.support.steps;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import hudson.model.Executor;
import hudson.model.Queue;

@WithJenkins
class ExecutorStepExecutionTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void placeholderTaskCachesComputedAffinityKey() throws Exception {
        assertPlaceholderTaskCachesComputedAffinityKey(
                "with-stage", "stage('outer') { node('nonexistent') { echo 'never' } }", "with-stage#outer");
        assertPlaceholderTaskCachesComputedAffinityKey(
                "without-stage", "node('nonexistent') { echo 'never' }", "without-stage");
    }

    private void assertPlaceholderTaskCachesComputedAffinityKey(String jobName, String script, String expected)
            throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, jobName);
        p.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        ExecutorStepExecution.PlaceholderTask task = null;
        try {
            task = waitForPlaceholderTask();
            Field cachedAffinityKey = ExecutorStepExecution.PlaceholderTask.class.getDeclaredField("cachedAffinityKey");
            cachedAffinityKey.setAccessible(true);

            ExecutorStepExecution.PlaceholderTask finalTask = task;
            Queue.withLock((Callable<Void>) () -> {
                cachedAffinityKey.set(finalTask, null);
                String computed = finalTask.getAffinityKey();
                assertEquals(expected, computed);
                assertEquals(computed, cachedAffinityKey.get(finalTask));

                String sentinel = computed + "#cached";
                cachedAffinityKey.set(finalTask, sentinel);
                assertEquals(sentinel, finalTask.getAffinityKey());
                return null;
            });
        } finally {
            Executor executor = b.getExecutor();
            if (executor != null) {
                executor.interrupt();
            } else {
                b.doStop();
            }
            r.waitForCompletion(b);
        }
    }

    private static ExecutorStepExecution.PlaceholderTask waitForPlaceholderTask() throws Exception {
        for (int i = 0; i < 100; i++) {
            for (Queue.Item item : Queue.getInstance().getItems()) {
                if (item.task instanceof ExecutorStepExecution.PlaceholderTask task) {
                    return task;
                }
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for PlaceholderTask to enter the queue");
        return null;
    }
}
