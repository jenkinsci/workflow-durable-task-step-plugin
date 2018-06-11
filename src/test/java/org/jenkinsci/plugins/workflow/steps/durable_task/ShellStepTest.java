package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.base.Predicate;
import hudson.FilePath;
import hudson.EnvVars;
import hudson.Functions;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.console.LineTransformationOutputStream;
import hudson.model.BallColor;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.Result;
import hudson.remoting.Channel;
import hudson.model.Slave;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import static org.hamcrest.Matchers.containsString;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsStepContext;
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
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable.Row;
import static org.junit.Assert.*;
import org.junit.Assume;
import static org.junit.Assume.assumeFalse;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class ShellStepTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Rule public LoggerRule logging = new LoggerRule();

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

    @Issue("JENKINS-41339")
    @Test public void nodePaths() throws Exception {
        DumbSlave slave = j.createSlave("slave", null, null);
        FreeStyleProject f = j.createFreeStyleProject("f"); // the control
        f.setAssignedNode(slave);
        f.getBuildersList().add(Functions.isWindows() ? new BatchFile("echo Path=%Path%") : new Shell("echo PATH=$PATH"));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('slave') {isUnix() ? sh('echo PATH=$PATH') : bat('echo Path=%Path%')}", true));
        // First check: syntax recommended in /help/system-config/nodeEnvironmentVariables.html.
        slave.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("PATH+ACME", Functions.isWindows() ? "C:\\acme\\bin" : "/opt/acme/bin")));
        j.assertLogContains(Functions.isWindows() ? ";C:\\acme\\bin;" : ":/opt/acme/bin:/", j.buildAndAssertSuccess(f)); // JRE also gets prepended
        j.assertLogContains(Functions.isWindows() ? "Path=C:\\acme\\bin;" : "PATH=/opt/acme/bin:/", j.buildAndAssertSuccess(p));
        // Second check: recursive expansion.
        slave.getNodeProperties().replace(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("PATH", Functions.isWindows() ? "C:\\acme\\bin;$PATH" : "/opt/acme/bin:$PATH")));
        j.assertLogContains(Functions.isWindows() ? ";C:\\acme\\bin;" : ":/opt/acme/bin:/", j.buildAndAssertSuccess(f));
        j.assertLogContains(Functions.isWindows() ? "Path=C:\\acme\\bin;" : "PATH=/opt/acme/bin:/", j.buildAndAssertSuccess(p));
    }

    @Test public void launcherDecorator() throws Exception {
        Assume.assumeTrue("TODO Windows equivalent TBD", new File("/usr/bin/nice").canExecute());
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {sh 'echo niceness=`nice`'}"));
        Assume.assumeThat("test only works if mvn test is not itself niced", j.getLog(j.assertBuildStatusSuccess(p.scheduleBuild2(0))), containsString("niceness=0"));
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

    @Issue("JENKINS-40734")
    @Test public void envWithShellChar() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {withEnv(['MONEY=big$$money']) {isUnix() ? sh('echo \"MONEY=$MONEY\"') : bat('echo \"MONEY=%MONEY%\"')}}", true));
        j.assertLogContains("MONEY=big$$money", j.buildAndAssertSuccess(p));
    }

    @Issue("JENKINS-26133")
    @Test public void configRoundTrip() throws Exception {
        ShellStep s = new ShellStep("echo hello");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertNull(s.getEncoding());
        assertFalse(s.isReturnStatus());
        s.setReturnStdout(true);
        s.setEncoding("ISO-8859-1");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertTrue(s.isReturnStdout());
        assertEquals("ISO-8859-1", s.getEncoding());
        assertFalse(s.isReturnStatus());
        s.setReturnStdout(false);
        s.setEncoding("UTF-8");
        s.setReturnStatus(true);
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertEquals("UTF-8", s.getEncoding());
        assertTrue(s.isReturnStatus());
    }

    @Issue("JENKINS-26133")
    @Test public void returnStdout() throws Exception {
        Assume.assumeTrue("TODO Windows equivalent TBD", new File("/usr/bin/tr").canExecute());
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("def msg; node {msg = sh(script: 'echo hello world | tr [a-z] [A-Z]', returnStdout: true).trim()}; echo \"it said ${msg}\""));
        j.assertLogContains("it said HELLO WORLD", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
        p.setDefinition(new CpsFlowDefinition("node {sh script: 'echo some problem here | tr [a-z] [A-Z]; exit 1', returnStdout: true}", true));
        j.assertLogContains("SOME PROBLEM HERE", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-26133")
    @Test public void returnStatus() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {echo \"truth is ${isUnix() ? sh(script: 'true', returnStatus: true) : bat(script: 'echo', returnStatus: true)} but falsity is ${isUnix() ? sh(script: 'false', returnStatus: true) : bat(script: 'type nonexistent' , returnStatus: true)}\"}", true));
        j.assertLogContains("truth is 0 but falsity is 1", j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Issue("JENKINS-38381")
    @Test public void remoteLogger() throws Exception {
        assumeFalse(Functions.isWindows()); // TODO create Windows equivalent
        final String credentialsId = "creds";
        final String username = "bob";
        final String password = "s3cr3t";
        UsernamePasswordCredentialsImpl c = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, credentialsId, "sample", username, password);
        CredentialsProvider.lookupStores(j.jenkins).iterator().next().addCredentials(Domain.global(), c);
        j.createSlave("remote", null, null);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node('master') {\n" +
            "  sh 'pwd'\n" +
            "}\n" +
            "node('remote') {\n" +
            "  sh 'pwd'\n" +
            "  sh 'echo on agent'\n" +
            "  withCredentials([[$class: 'UsernamePasswordBinding', variable: 'USERPASS', credentialsId: '" + credentialsId + "']]) {\n" +
            "    sh 'set +x; echo curl -u $USERPASS http://server/'\n" +
            "  }\n" +
            "}", true));
        WorkflowRun b = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("+ pwd [master #1]", b);
        j.assertLogContains("+ pwd [master #1 → remote #", b);
        j.assertLogContains("on agent [master #1 → remote #", b);
        j.assertLogNotContains(password, b);
        j.assertLogContains("curl -u **** http://server/ [master #1 → remote #", b);
    }
    @TestExtension("remoteLogger") public static class LogFile extends PipelineLogFile {
        @Override protected BuildListener listenerFor(WorkflowRun b) throws IOException, InterruptedException {
            return new RemotableBuildListener(logFile(b));
        }
        @Override protected InputStream logFor(WorkflowRun b, long start) throws IOException {
            return Channels.newInputStream(FileChannel.open(logFile(b).toPath(), StandardOpenOption.READ).position(start));
        }
        File logFile(WorkflowRun b) {
            return new File(b.getRootDir(), "special.log");
        }
    }
    private static class RemotableBuildListener implements BuildListener {
        private static final long serialVersionUID = 1;
        /** counts which instance this is per JVM */
        private static final AtomicInteger instantiationCounter = new AtomicInteger();
        /** location of log file streamed to by multiple sources */
        private final File logFile;
        /** records allocation & deserialization history; e.g., {@code master #1 → agent #1} */
        private final String id;
        private transient PrintStream logger;
        RemotableBuildListener(File logFile) {
            this(logFile, "master #" + instantiationCounter.incrementAndGet());
        }
        private RemotableBuildListener(File logFile, String id) {
            this.logFile = logFile;
            this.id = id;
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
        private Object writeReplace() {
            /* To see serialization happening from BourneShellScript.launchWithCookie & FileMonitoringController.watch:
            Thread.dumpStack();
            */
            String name = Channel.current().getName();
            return new RemotableBuildListener(logFile, id + " → " + name + " #" + instantiationCounter.incrementAndGet());
        }
    }

    @Issue("JENKINS-31096")
    @Test public void encoding() throws Exception {
        // Like JenkinsRule.createSlave but passing a system encoding:
        Slave remote = new DumbSlave("remote", tmp.getRoot().getAbsolutePath(),
            new SimpleCommandLauncher("'" + System.getProperty("java.home") + "/bin/java' -Dfile.encoding=ISO-8859-2 -jar '" + new File(j.jenkins.getJnlpJars("slave.jar").getURL().toURI()) + "'"));
        j.jenkins.addNode(remote);
        j.waitOnline(remote);
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        FilePath ws;
        while ((ws = remote.getWorkspaceFor(p)) == null) {
            Thread.sleep(100);
        }
        ws.child("message").write("Čau ty vole!\n", "ISO-8859-2");
        p.setDefinition(new CpsFlowDefinition("node('remote') {if (isUnix()) {sh 'cat message'} else {bat 'type message'}}", true));
        j.assertLogContains("Čau ty vole!", j.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("node('remote') {echo(/received: ${isUnix() ? sh(script: 'cat message', returnStdout: true) : bat(script: '@type message', returnStdout: true)}/)}", true)); // http://stackoverflow.com/a/8486061/12916
        j.assertLogContains("received: Čau ty vole!", j.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("node('remote') {if (isUnix()) {sh script: 'cat message', encoding: 'US-ASCII'} else {bat script: 'type message', encoding: 'US-ASCII'}}", true));
        j.assertLogContains("�au ty vole!", j.buildAndAssertSuccess(p));
        ws.child("message").write("¡Čau → there!\n", "UTF-8");
        p.setDefinition(new CpsFlowDefinition("node('remote') {if (isUnix()) {sh script: 'cat message', encoding: 'UTF-8'} else {bat script: 'type message', encoding: 'UTF-8'}}", true));
        j.assertLogContains("¡Čau → there!", j.buildAndAssertSuccess(p));
    }

    @Issue("JENKINS-34021")
    @Test public void deadStep() throws Exception {
        logging.record(DurableTaskStep.class, Level.INFO).record(CpsStepContext.class, Level.INFO).capture(100);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("try {node {isUnix() ? sh('sleep 1000000') : bat('ping -t 127.0.0.1 > nul')}} catch (e) {sleep 1; throw e}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage(Functions.isWindows() ? ">ping" : "+ sleep", b);
        b.doTerm();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.ABORTED, b);
        for (LogRecord record : logging.getRecords()) {
            assertNull(record.getThrown());
        }
    }
    
    @Issue("JENKINS-44521")
    @Test public void shouldInvokeLauncherDecoratorForShellStep() throws Exception {
        DumbSlave slave = j.createSlave("slave", null, null);
        
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('slave') {isUnix() ? sh('echo INJECTED=$INJECTED') : bat('echo INJECTED=%INJECTED%')}", true));
        WorkflowRun run = j.buildAndAssertSuccess(p);
        
        j.assertLogContains("INJECTED=MYVAR-" + slave.getNodeName(), run);
        
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
    
    @TestExtension(value = "shouldInvokeLauncherDecoratorForShellStep")
    public static final class MyNodeLauncherDecorator extends LauncherDecorator {

        @Override
        public Launcher decorate(Launcher lnchr, Node node) {
            // Just inject the environment variable
            Map<String, String> env = new HashMap<>();
            env.put("INJECTED", "MYVAR-" + node.getNodeName());
            return lnchr.decorateByEnv(new EnvVars(env));
        }  
    }
}

