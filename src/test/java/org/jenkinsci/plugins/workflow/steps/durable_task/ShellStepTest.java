package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.google.common.base.Predicate;
import hudson.Functions;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.model.BallColor;
import hudson.model.Node;
import hudson.model.Result;
import java.io.File;
import java.io.Serializable;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable.Row;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class ShellStepTest extends Assert {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule j = new JenkinsRule();

    /**
     * Failure in the shell script should mark the step as red
     */
    @Test
    public void failureShouldMarkNodeRed() throws Exception {
        // job setup
        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(Functions.isWindows() ? "node {bat 'whatever'}" : "node {sh 'false'}"));

        // get the build going, and wait until workflow pauses
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, foo.scheduleBuild2(0).get());

        boolean found = false;
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        for (Row r : t.getRows()) {
            if (r.getNode() instanceof StepAtomNode) {
                StepAtomNode sa = (StepAtomNode) r.getNode();
                if (sa.getDescriptor().getFunctionName().matches("sh|bat")) {
                    assertSame(BallColor.RED, sa.getIconColor());
                    found = true;
                }
            }
        }

        assertTrue(found);
    }

    /**
     * Abort a running workflow to ensure that the process is terminated.
     */
    @Test
    public void abort() throws Exception {
        File tmp = File.createTempFile("jenkins","test");
        tmp.delete();

        // job setup
        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(Functions.isWindows() ?
            "node {bat($/:loop\r\n" +
                "echo . >" + tmp + "\r\n" +
                "ping -n 2 127.0.0.1 >nul\r\n" + // http://stackoverflow.com/a/4317036/12916
                "goto :loop/$)}" :
            "node {sh 'while true; do touch " + tmp + "; sleep 1; done'}"));

        // get the build going, and wait until workflow pauses
        WorkflowRun b = foo.scheduleBuild2(0).getStartCondition().get();

        // at this point the file should be being touched
        while (!tmp.exists()) {
            Thread.sleep(100);
        }

        b.getExecutor().interrupt();

        // touching should have stopped
        final long refTimestamp = tmp.lastModified();
        ensureForWhile(5000, tmp, new Predicate<File>() {
            @Override
            public boolean apply(File tmp) {
                return refTimestamp==tmp.lastModified();
            }
        });

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
    }

    @Test public void launcherDecorator() throws Exception {
        Assume.assumeTrue("TODO Windows equivalent TBD", new File("/usr/bin/nice").canExecute());
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {nice {sh 'echo niceness=`nice`'}}"));
        j.assertLogContains("niceness=10", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
        p.setDefinition(new CpsFlowDefinition("node {nice {nice {sh 'echo niceness=`nice`'}}}"));
        j.assertLogContains("niceness=19", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }
    public static class NiceStep extends AbstractStepImpl {
        @DataBoundConstructor public NiceStep() {}
        public static class Execution extends AbstractStepExecutionImpl {
            private static final long serialVersionUID = 1;
            @Override public boolean start() throws Exception {
                getContext().newBodyInvoker().
                        withContext(BodyInvoker.mergeLauncherDecorators(getContext().get(LauncherDecorator.class), new Decorator())).
                        withCallback(BodyExecutionCallback.wrap(getContext())).
                        start();
                return false;
            }
            @Override public void stop(Throwable cause) throws Exception {}
        }
        private static class Decorator extends LauncherDecorator implements Serializable {
            private static final long serialVersionUID = 1;
            @Override public Launcher decorate(Launcher launcher, Node node) {
                return launcher.decorateByPrefix("nice");
            }
        }
        @TestExtension("launcherDecorator") public static class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {
                return "nice";
            }
            @Override public String getDisplayName() {
                return "Nice";
            }
            @Override public boolean takesImplicitBlockArgument() {
                return true;
            }
        }
    }

    @Issue("JENKINS-26133")
    @Test public void configRoundTrip() throws Exception {
        ShellStep s = new ShellStep("echo hello");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertEquals(DurableTaskStep.DurableTaskStepDescriptor.defaultEncoding, s.getEncoding());
        assertFalse(s.isReturnStatus());
        s.setReturnStdout(true);
        s.setEncoding("ISO-8859-1");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertTrue(s.isReturnStdout());
        assertEquals("ISO-8859-1", s.getEncoding());
        assertFalse(s.isReturnStatus());
        s.setReturnStdout(false);
        s.setEncoding(DurableTaskStep.DurableTaskStepDescriptor.defaultEncoding);
        s.setReturnStatus(true);
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertEquals(DurableTaskStep.DurableTaskStepDescriptor.defaultEncoding, s.getEncoding());
        assertTrue(s.isReturnStatus());
    }

    @Issue("JENKINS-26133")
    @Test public void returnStdout() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("def msg; node {msg = sh(script: 'echo hello world | tr [a-z] [A-Z]', returnStdout: true).trim()}; echo \"it said ${msg}\""));
        j.assertLogContains("it said HELLO WORLD", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-26133")
    @Test public void returnStatus() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {echo \"truth is ${sh script: 'true', returnStatus: true} but falsity is ${sh script: 'false', returnStatus: true}\"}"));
        j.assertLogContains("truth is 0 but falsity is 1", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    /**
     * Asserts that the predicate remains true up to the given timeout.
     */
    private <T> void ensureForWhile(int timeout, T o, Predicate<T> predicate) throws Exception {
        long goal = System.currentTimeMillis()+timeout;
        while (System.currentTimeMillis()<goal) {
            if (!predicate.apply(o))
                throw new AssertionError(predicate);
            Thread.sleep(100);
        }
    }
}

