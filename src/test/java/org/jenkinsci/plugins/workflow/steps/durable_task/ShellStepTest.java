package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.google.common.base.Predicate;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.console.LineTransformationOutputStream;
import hudson.model.BallColor;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.model.Result;
import hudson.remoting.Channel;
import hudson.slaves.CommandLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.security.SlaveToMasterCallable;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.console.PipelineLogFile;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.support.actions.LessAbstractTaskListener;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable.Row;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class ShellStepTest extends Assert {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

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
        p.setDefinition(new CpsFlowDefinition("node {sh script: 'echo some problem here | tr [a-z] [A-Z]; exit 1', returnStdout: true}", true));
        j.assertLogContains("SOME PROBLEM HERE", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-26133")
    @Test public void returnStatus() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {echo \"truth is ${sh script: 'true', returnStatus: true} but falsity is ${sh script: 'false', returnStatus: true}\"}"));
        j.assertLogContains("truth is 0 but falsity is 1", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-38381")
    @Test public void remoteLogger() throws Exception {
        j.createSlave("remote", null, null);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('master') {sh 'pwd'}; node('remote') {sh 'pwd'; sh 'echo on agent'}", true));
        WorkflowRun b = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        //new ProcessBuilder("ls", "-la", b.getRootDir().getAbsolutePath()).inheritIO().start().waitFor();
        j.assertLogContains("+ pwd [master #1]", b);
        j.assertLogContains("+ pwd [master #1 → remote #", b);
        j.assertLogContains("on agent [master #1 → remote #", b);
    }
    @TestExtension("remoteLogger") public static class LogFile extends PipelineLogFile {
        @Override protected BuildListener listenerFor(WorkflowRun b) throws IOException, InterruptedException {
            return new RemotableBuildListener(logFile(b));
        }
        @Override protected InputStream logFor(WorkflowRun b) throws IOException {
            return new FileInputStream(logFile(b));
        }
        File logFile(WorkflowRun b) {
            return new File(b.getRootDir(), "special.log");
        }
    }
    private static class RemotableBuildListener extends LessAbstractTaskListener implements BuildListener {
        private static final long serialVersionUID = 1;
        /** counts which instance this is per JVM */
        private static final AtomicInteger instantiationCounter = new AtomicInteger();
        /** location of log file streamed to by multiple sources */
        private final File logFile;
        /** records allocation & deserialization history; e.g., {@code master #1 → agent #1} */
        private String id;
        private transient PrintStream logger;
        RemotableBuildListener(File logFile) {
            this.logFile = logFile;
            id = "master #" + instantiationCounter.incrementAndGet();
        }
        @Override public PrintStream getLogger() {
            if (logger == null) {
                final OutputStream fos;
                try {
                    fos = new FileOutputStream(logFile, true);
                } catch (FileNotFoundException x) {
                    throw new AssertionError(x);
                }
                logger = new PrintStream(new LineTransformationOutputStream() {
                    @Override protected void eol(byte[] b, int len) throws IOException {
                        fos.write(b, 0, len - 1); // all but NL
                        fos.write((" [" + id + "]").getBytes(StandardCharsets.UTF_8));
                        fos.write(b[len - 1]); // NL
                    }
                });
            }
            return logger;
        }
        /* To see serialization happening from BourneShellScript.launchWithCookie & FileMonitoringController.watch:
        private Object writeReplace() {
            Thread.dumpStack();
            return this;
        }
        */
        private Object readResolve() throws Exception {
            String name = Channel.current().call(new FindMyOwnName());
            id += " → " + name + " #" + instantiationCounter.incrementAndGet();
            return this;
        }
        // Awkward, but from the remote side the channel is currently just called `channel`, which is not very informative.
        // Could be done also by calling Channel.current from writeReplace and stashing the channel name on the master side.
        private static class FindMyOwnName extends SlaveToMasterCallable<String,RuntimeException> {
            @Override public String call() {
                return Channel.current().getName();
            }
        }
    }

    @Issue("JENKINS-31096")
    @Test public void encoding() throws Exception {
        // Like JenkinsRule.createSlave but passing a system encoding:
        Node remote = new DumbSlave("remote", "", tmp.getRoot().getAbsolutePath(), "1", Node.Mode.NORMAL, "",
            new CommandLauncher("'" + System.getProperty("java.home") + "/bin/java' -Dfile.encoding=ISO-8859-2 -jar '" + new File(j.jenkins.getJnlpJars("slave.jar").getURL().toURI()) + "'"),
            RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
        j.jenkins.addNode(remote);
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        FilePath ws;
        while ((ws = remote.getWorkspaceFor(p)) == null) {
            Thread.sleep(100);
        }
        // `echo -e '\xC8au ty vole!'` works from /bin/bash on Ubuntu but not /bin/sh; anyway nonportable
        ws.child("message").write("Čau ty vole!\n", "ISO-8859-2");
        p.setDefinition(new CpsFlowDefinition("node('remote') {sh 'cat message'}", true));
        j.assertLogContains("Čau ty vole!", j.buildAndAssertSuccess(p));
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

