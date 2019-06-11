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
import hudson.MarkupText;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleLogFilter;
import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;
import hudson.model.BallColor;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.remoting.Channel;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.annotation.CheckForNull;
import jenkins.util.JenkinsJVM;
import org.apache.commons.lang.StringUtils;

import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.durabletask.FileMonitoringTask;

import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsStepContext;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepTypePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.BrokenLogStorage;
import org.jenkinsci.plugins.workflow.log.FileLogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorageFactory;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable.Row;
import static org.junit.Assert.*;
import org.junit.Assume;
import static org.junit.Assume.assumeFalse;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class ShellStepTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Rule public ErrorCollector errors = new ErrorCollector();
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

    @Issue("JENKINS-52943")
    @Test
    public void stepDescription() throws Exception {
        // job setup
        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(Functions.isWindows() ? "node {" +
                "bat 'echo first';" +
                "bat returnStdout: true, script: 'echo second';" +
        "}" : "node {" +
                "sh 'echo first'; " +
                "sh returnStdout: true, script: 'echo second'" +
        "}", true));

        WorkflowRun b = j.buildAndAssertSuccess(foo);

        ArrayList<String> args = new ArrayList<>();
        List<FlowNode> shellStepNodes = new DepthFirstScanner().filteredNodes(b.getExecution(), new NodeStepTypePredicate(Functions.isWindows() ? "bat" : "sh"));
        assertThat(shellStepNodes, hasSize(2));
        for(FlowNode n : shellStepNodes) {
            args.add(ArgumentsAction.getStepArgumentsAsString(n));
        }

        assertThat(args, containsInAnyOrder("echo first", "echo second"));
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
        assertEquals("", s.getLabel());
        
        s.setReturnStdout(true);
        s.setEncoding("ISO-8859-1");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertTrue(s.isReturnStdout());
        assertEquals("ISO-8859-1", s.getEncoding());
        assertFalse(s.isReturnStatus());
        assertEquals("", s.getLabel());
        
        s.setReturnStdout(false);
        s.setEncoding("UTF-8");
        s.setReturnStatus(true);
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertEquals("UTF-8", s.getEncoding());
        assertTrue(s.isReturnStatus());
        assertEquals("", s.getLabel());
        
        s.setLabel("Round Trip Test");
        s = new StepConfigTester(j).configRoundTrip(s);
        assertEquals("echo hello", s.getScript());
        assertFalse(s.isReturnStdout());
        assertEquals("UTF-8", s.getEncoding());
        assertTrue(s.isReturnStatus());
        assertEquals("Round Trip Test", s.getLabel());
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
    
    
    @Test public void label() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {isUnix() ? sh(script: 'true', label: 'Step with label') : bat(script: 'echo', label: 'Step with label')}", true));
        
        WorkflowRun b = j.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        boolean found = false;
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        for (Row r : t.getRows()) {
            if (r.getDisplayName().equals("Step with label")) {
                found = true;
            }
        }

        assertTrue(found);
    }
    
    @Test public void labelShortened() throws Exception {
        String singleLabel= StringUtils.repeat("0123456789", 10);
        String label = singleLabel + singleLabel;
        
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {isUnix() ? sh(script: 'true', label: '" + label + "') : bat(script: 'echo', label: '" + label + "')}", true));
        
        WorkflowRun b = j.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        boolean found = false;
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        for (Row r : t.getRows()) {
            if (r.getDisplayName().equals(singleLabel)) {
                found = true;
            }
        }

        assertTrue(found);
    }

    @Issue("JENKINS-38381")
    @Test public void remoteLogger() throws Exception {
        DurableTaskStep.USE_WATCHING = true;
        try {
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
            "  withCredentials([usernameColonPassword(variable: 'USERPASS', credentialsId: '" + credentialsId + "')]) {\n" +
            "    sh 'set +x; echo curl -u $USERPASS http://server/'\n" +
            "  }\n" +
            "}", true));
        WorkflowRun b = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("+ pwd [master]", b);
        j.assertLogContains("+ PWD [master → remote]", b);
        j.assertLogContains("ON AGENT [master → remote]", b);
        j.assertLogNotContains(password, b);
        j.assertLogNotContains(password.toUpperCase(Locale.ENGLISH), b);
        j.assertLogContains("CURL -U **** HTTP://SERVER/ [master → remote]", b);
        } finally {
            DurableTaskStep.USE_WATCHING = false;
        }
    }
    @TestExtension("remoteLogger") public static class LogFile implements LogStorageFactory {
        @Override public LogStorage forBuild(FlowExecutionOwner b) {
            final LogStorage base;
            try {
                base = FileLogStorage.forFile(new File(b.getRootDir(), "special.log"));
            } catch (IOException x) {
                return new BrokenLogStorage(x);
            }
            return new LogStorage() {
                @Override public BuildListener overallListener() throws IOException, InterruptedException {
                    return new RemotableBuildListener(base.overallListener());
                }
                @Override public TaskListener nodeListener(FlowNode node) throws IOException, InterruptedException {
                    return new RemotableBuildListener(base.nodeListener(node));
                }
                @Override public AnnotatedLargeText<FlowExecutionOwner.Executable> overallLog(FlowExecutionOwner.Executable build, boolean complete) {
                    return base.overallLog(build, complete);
                }
                @Override public AnnotatedLargeText<FlowNode> stepLog(FlowNode node, boolean complete) {
                    return base.stepLog(node, complete);
                }
            };
        }
    }
    private static class RemotableBuildListener implements BuildListener {
        private static final long serialVersionUID = 1;
        /** actual implementation */
        private final TaskListener delegate;
        /** records allocation & deserialization history; e.g., {@code master → agent} */
        private final String id;
        private transient PrintStream logger;
        RemotableBuildListener(TaskListener delegate) {
            this(delegate, "master");
        }
        private RemotableBuildListener(TaskListener delegate, String id) {
            this.delegate = delegate;
            this.id = id;
        }
        @Override public PrintStream getLogger() {
            if (logger == null) {
                final OutputStream os = delegate.getLogger();
                logger = new PrintStream(new LineTransformationOutputStream() {
                    @Override protected void eol(byte[] b, int len) throws IOException {
                        for (int i = 0; i < len - 1; i++) { // all but NL
                            os.write(id.equals("master") ? b[i] : Character.toUpperCase(b[i]));
                        }
                        os.write((" [" + id + "]").getBytes(StandardCharsets.UTF_8));
                        os.write(b[len - 1]); // NL
                    }
                    @Override public void flush() throws IOException {
                        os.flush();
                    }
                    @Override public void close() throws IOException {
                        super.close();
                        os.close();
                    }
                }, true);
            }
            return logger;
        }
        private Object writeReplace() {
            /* To see serialization happening from BourneShellScript.launchWithCookie & FileMonitoringController.watch:
            Thread.dumpStack();
            */
            String name = Channel.current().getName();
            return new RemotableBuildListener(delegate, id + " → " + name);
        }
    }

    @Issue("JENKINS-54133")
    @Test public void remoteConsoleNotes() throws Exception {
        DurableTaskStep.USE_WATCHING = true;
        try {
        assumeFalse(Functions.isWindows()); // TODO create Windows equivalent
        j.createSlave("remote", null, null);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node('master') {\n" +
            "  markUp {\n" +
            "    sh 'echo hello from master'\n" +
            "  }\n" +
            "}\n" +
            "node('remote') {\n" +
            "  markUp {\n" +
            "    sh 'echo hello from agent'\n" +
            "  }\n" +
            "  markUp(smart: true) {\n" +
            "    sh 'echo hello from halfway in between'\n" +
            "  }\n" +
            "}", true));
        logging.recordPackage(ConsoleNote.class, Level.FINE);
        WorkflowRun b = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        b.getLogText().writeRawLogTo(0, System.err);
        StringWriter w = new StringWriter();
        b.getLogText().writeHtmlTo(0, w);
        assertThat("a ConsoleNote created in the master is trusted", w.toString(), containsString("<b>hello</b> from master"));
        assertThat("but this one was created in the agent and is discarded", w.toString(), containsString("hello from agent"));
        assertThat("however we can pass it from the master to agent", w.toString(), containsString("<b>hello</b> from halfway in between"));
        } finally {
            DurableTaskStep.USE_WATCHING = false;
        }
    }
    public static final class MarkUpStep extends Step {
        @DataBoundSetter public boolean smart;
        @DataBoundConstructor public MarkUpStep() {}
        @Override public StepExecution start(StepContext context) throws Exception {
            return new Exec(context, smart);
        }
        private static final class Exec extends StepExecution {
            final boolean smart;
            Exec(StepContext context, boolean smart) {
                super(context);
                this.smart = smart;
            }
            @Override public boolean start() throws Exception {
                getContext().newBodyInvoker().
                    withContext(BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), new Filter(smart))).
                    withCallback(BodyExecutionCallback.wrap(getContext())).
                    start();
                return false;
            }
        }
        private static final class Filter extends ConsoleLogFilter implements Serializable {
            private final @CheckForNull byte[] note;
            Filter(boolean smart) throws IOException {
                JenkinsJVM.checkJenkinsJVM();
                if (smart) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    new HelloNote().encodeTo(baos);
                    note = baos.toByteArray();
                } else {
                    note = null;
                }
            }
            @SuppressWarnings("rawtypes")
            @Override public OutputStream decorateLogger(Run _ignore, final OutputStream logger) throws IOException, InterruptedException {
                return new LineTransformationOutputStream() {
                    @Override protected void eol(byte[] b, int len) throws IOException {
                        if (b.length >= 5 && b[0] == 'h' && b[1] == 'e' && b[2] == 'l' && b[3] == 'l' && b[4] == 'o') {
                            if (note != null) {
                                logger.write(note);
                            } else {
                                new HelloNote().encodeTo(logger);
                            }
                        }
                        logger.write(b, 0, len);
                    }
                    @Override public void close() throws IOException {
                        super.close();
                        logger.close();
                    }
                    @Override public void flush() throws IOException {
                        logger.flush();
                    }
                };
            }
        }
        private static final class HelloNote extends ConsoleNote<Object> {
            @SuppressWarnings("rawtypes") // TODO pending 2.145 generics fixes
            @Override public ConsoleAnnotator annotate(Object context, MarkupText text, int charPos) {
                text.addMarkup(0, 5, "<b>", "</b>");
                return null;
            }
        }
        @TestExtension public static final class DescriptorImpl extends StepDescriptor {
            @Override public String getFunctionName() {
                return "markUp";
            }
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.emptySet();
            }
            @Override public boolean takesImplicitBlockArgument() {
                return true;
            }
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

    @Ignore("TODO missing final line and in some cases even the `+ set +x` (but passes without withCredentials)")
    @Test public void missingNewline() throws Exception {
        assumeFalse(Functions.isWindows()); // TODO create Windows equivalent
        String credentialsId = "creds";
        String username = "bob";
        String password = "s3cr3t";
        UsernamePasswordCredentialsImpl c = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, credentialsId, "sample", username, password);
        CredentialsProvider.lookupStores(j.jenkins).iterator().next().addCredentials(Domain.global(), c);
        j.createSlave("remote", null, null);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node('master') {\n" +
            "  withCredentials([usernameColonPassword(variable: 'USERPASS', credentialsId: '" + credentialsId + "')]) {\n" +
            "    sh 'set +x; printf \"some local output\"'\n" +
            "  }\n" +
            "}\n" +
            "node('remote') {\n" +
            "  withCredentials([usernameColonPassword(variable: 'USERPASS', credentialsId: '" + credentialsId + "')]) {\n" +
            "    sh 'set +x; printf \"some remote output\"'\n" +
            "  }\n" +
            "}", true));
        Callable<Void> test = () -> {
            WorkflowRun b = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
            j.assertLogContains("some local output", b);
            j.assertLogContains("some remote output", b);
            return null;
        };
        boolean origUseWatching = DurableTaskStep.USE_WATCHING;
        try {
            DurableTaskStep.USE_WATCHING = false;
            errors.checkSucceeds(test);
            DurableTaskStep.USE_WATCHING = true;
            errors.checkSucceeds(test);
        } finally {
            DurableTaskStep.USE_WATCHING = origUseWatching;
        }
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
    
    @Issue("JENKINS-49707")
    @Test public void removingAgentIsFatal() throws Exception {
        logging.record(DurableTaskStep.class, Level.FINE).record(FileMonitoringTask.class, Level.FINE).record(ExecutorStepExecution.class, Level.FINE);
        DumbSlave s = j.createSlave("remote", null, null);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {isUnix() ? sh('sleep 1000000') : bat('ping -t 127.0.0.1 > nul')}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage(Functions.isWindows() ? ">ping" : "+ sleep", b);
        j.jenkins.removeNode(s);
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        j.waitForMessage(new ExecutorStepExecution.RemovedNodeCause().getShortDescription(), b);
    }

    @Issue("JENKINS-44521")
    @Test public void shouldInvokeLauncherDecoratorForShellStep() throws Exception {
        DumbSlave slave = j.createSlave("slave", null, null);
        
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('slave') {isUnix() ? sh('echo INJECTED=$INJECTED') : bat('echo INJECTED=%INJECTED%')}", true));
        WorkflowRun run = j.buildAndAssertSuccess(p);
        
        j.assertLogContains("INJECTED=MYVAR-" + slave.getNodeName(), run);
        
    }

    @Issue("JENKINS-28822")
    @Test public void interruptingAbortsBuild() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {\n" +
                "  timeout(time: 1, unit: 'SECONDS') {" +
                (Functions.isWindows()
                        ? "bat 'ping -n 6 127.0.0.1 >nul'\n"
                        : "sh 'sleep 5'\n") +
                "  }" +
                "}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        // Would have failed with Result.FAILURE before https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/75.
        j.assertBuildStatus(Result.ABORTED, b);
        j.assertLogContains("Timeout has been exceeded", b);
    }

    @Issue("JENKINS-28822")
    @Test public void interruptingAbortsBuildEvenWithReturnStatus() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node() {\n" +
                "  timeout(time: 1, unit: 'SECONDS') {\n" +
                (Functions.isWindows()
                        ? "bat(returnStatus: true, script: 'ping -n 6 127.0.0.1 >nul')\n"
                        : "sh(returnStatus: true, script: 'sleep 5')\n") +
                "  }\n" +
                "}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        // Would have succeeded before https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/75.
        j.assertBuildStatus(Result.ABORTED, b);
        j.waitForMessage("Timeout has been exceeded", b); // TODO assertLogContains fails unless a sleep is introduced; possible race condition in waitForCompletion
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

