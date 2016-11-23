/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

import com.google.inject.Inject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.util.Timer;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Runs an durable task on a slave, such as a shell script.
 */
public abstract class DurableTaskStep extends AbstractStepImpl {

    private static final Logger LOGGER = Logger.getLogger(DurableTaskStep.class.getName());

    private boolean returnStdout;
    private String encoding = DurableTaskStepDescriptor.defaultEncoding;
    private boolean returnStatus;

    protected abstract DurableTask task();

    public boolean isReturnStdout() {
        return returnStdout;
    }

    @DataBoundSetter public void setReturnStdout(boolean returnStdout) {
        this.returnStdout = returnStdout;
    }

    public String getEncoding() {
        return encoding;
    }

    @DataBoundSetter public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public boolean isReturnStatus() {
        return returnStatus;
    }

    @DataBoundSetter public void setReturnStatus(boolean returnStatus) {
        this.returnStatus = returnStatus;
    }

    public abstract static class DurableTaskStepDescriptor extends AbstractStepDescriptorImpl {

        public static final String defaultEncoding = "UTF-8";

        protected DurableTaskStepDescriptor() {
            super(Execution.class);
        }

        public FormValidation doCheckEncoding(@QueryParameter boolean returnStdout, @QueryParameter String encoding) {
            try {
                Charset.forName(encoding);
            } catch (Exception x) {
                return FormValidation.error(x, "Unrecognized encoding");
            }
            if (!returnStdout && !encoding.equals(DurableTaskStepDescriptor.defaultEncoding)) {
                return FormValidation.warning("encoding is ignored unless returnStdout is checked.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckReturnStatus(@QueryParameter boolean returnStdout, @QueryParameter boolean returnStatus) {
            if (returnStdout && returnStatus) {
                return FormValidation.error("You may not select both returnStdout and returnStatus.");
            }
            return FormValidation.ok();
        }

    }

    /**
     * Represents one task that is believed to still be running.
     */
    @Restricted(NoExternalUse.class)
    @SuppressFBWarnings(value="SE_TRANSIENT_FIELD_NOT_RESTORED", justification="recurrencePeriod is set in onResume, not deserialization")
    public static final class Execution extends AbstractStepExecutionImpl implements Runnable {

        private static final long MIN_RECURRENCE_PERIOD = 250; // ¼s
        private static final long MAX_RECURRENCE_PERIOD = 15000; // 15s
        private static final float RECURRENCE_PERIOD_BACKOFF = 1.2f;

        @Inject(optional=true) private transient DurableTaskStep step;
        @StepContextParameter private transient FilePath ws;
        @StepContextParameter private transient EnvVars env;
        @StepContextParameter private transient Launcher launcher;
        @StepContextParameter private transient TaskListener listener;
        private transient long recurrencePeriod;
        private Controller controller;
        private String node;
        private String remote;
        private boolean returnStdout; // serialized default is false
        private String encoding; // serialized default is irrelevant
        private boolean returnStatus; // serialized default is false

        @Override public boolean start() throws Exception {
            returnStdout = step.returnStdout;
            encoding = step.encoding;
            returnStatus = step.returnStatus;
            node = FilePathUtils.getNodeName(ws);
            if (step instanceof BatchScriptStep || step instanceof ShellStep)  {
                FlowNode fn = getContext().get(FlowNode.class);
                String script = (step instanceof BatchScriptStep) ? ((BatchScriptStep)step).getScript() : ((ShellStep)step).getScript();
                fn.addAction(new ScriptArgumentAction(script));
            }
            DurableTask task = step.task();
            if (returnStdout) {
                task.captureOutput();
            }
            controller = task.launch(env, ws, launcher, listener);
            this.remote = ws.getRemote();
            setupTimer();
            return false;
        }

        private @CheckForNull FilePath getWorkspace() throws AbortException {
            if (ws == null) {
                ws = FilePathUtils.find(node, remote);
                if (ws == null) {
                    LOGGER.log(Level.FINE, "Jenkins is not running, no such node {0}, or it is offline", node);
                    return null;
                }
            }
            boolean directory;
            try {
                directory = ws.isDirectory();
            } catch (Exception x) {
                // RequestAbortedException, ChannelClosedException, EOFException, wrappers thereof…
                LOGGER.log(Level.FINE, node + " is evidently offline now", x);
                ws = null;
                return null;
            }
            if (!directory) {
                throw new AbortException("missing workspace " + remote + " on " + node);
            }
            return ws;
        }

        private @Nonnull PrintStream logger() {
            TaskListener l = listener;
            if (l == null) {
                StepContext context = getContext();
                try {
                    l = context.get(TaskListener.class);
                    if (l != null) {
                        LOGGER.log(Level.WARNING, "JENKINS-34021: DurableTaskStep.Execution.listener not restored in {0}", context);
                    } else {
                        LOGGER.log(Level.WARNING, "JENKINS-34021: TaskListener not even available upon request in {0}", context);
                        l = new LogTaskListener(LOGGER, Level.FINE);
                    }
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "JENKINS-34021: could not get TaskListener in " + context, x);
                    l = new LogTaskListener(LOGGER, Level.FINE);
                }
            }
            return l.getLogger();
        }

        @Override public void stop(Throwable cause) throws Exception {
            FilePath workspace = getWorkspace();
            if (workspace != null) {
                logger().println("Sending interrupt signal to process");
                LOGGER.log(Level.FINE, "stopping process", cause);
                controller.stop(workspace, launcher);
            } else {
                logger().println("Could not connect to " + node + " to send interrupt signal to process");
            }
        }

        @Override public String getStatus() {
            try {
                FilePath workspace = getWorkspace();
                if (workspace != null) {
                    return controller.getDiagnostics(workspace, launcher);
                } else {
                    return "waiting to reconnect to " + remote + " on " + node;
                }
            } catch (IOException | InterruptedException x) {
                return "failed to look up workspace: " + x;
            }
        }

        /** Checks for progress or completion of the external task. */
        @Override public void run() {
            try {
                check();
            } finally {
                if (recurrencePeriod > 0) {
                    Timer.get().schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
                }
            }
        }

        private void check() {
            FilePath workspace;
            try {
                workspace = getWorkspace();
            } catch (AbortException x) {
                recurrencePeriod = 0;
                getContext().onFailure(x);
                return;
            }
            if (workspace == null) {
                return; // slave not yet ready, wait for another day
            }
            // Do not allow this to take more than 10s for any given task:
            final AtomicReference<Thread> t = new AtomicReference<>(Thread.currentThread());
            Timer.get().schedule(new Runnable() {
                @Override public void run() {
                    Thread _t = t.get();
                    if (_t != null) {
                        _t.interrupt();
                    }
                }
            }, 10, TimeUnit.SECONDS);
            try {
                if (controller.writeLog(workspace, logger())) {
                    getContext().saveState();
                    recurrencePeriod = MIN_RECURRENCE_PERIOD; // got output, maybe we will get more soon
                } else {
                    recurrencePeriod = Math.min((long) (recurrencePeriod * RECURRENCE_PERIOD_BACKOFF), MAX_RECURRENCE_PERIOD);
                }
                Integer exitCode = controller.exitStatus(workspace, launcher);
                if (exitCode == null) {
                    LOGGER.log(Level.FINE, "still running in {0} on {1}", new Object[] {remote, node});
                } else {
                    if (controller.writeLog(workspace, logger())) {
                        LOGGER.log(Level.FINE, "last-minute output in {0} on {1}", new Object[] {remote, node});
                    }
                    t.set(null); // do not interrupt cleanup
                    if (returnStatus || exitCode == 0) {
                        getContext().onSuccess(returnStatus ? exitCode : returnStdout ? new String(controller.getOutput(workspace, launcher), encoding) : null);
                    } else {
                        if (returnStdout) {
                            listener.getLogger().write(controller.getOutput(workspace, launcher)); // diagnostic
                        }
                        getContext().onFailure(new AbortException("script returned exit code " + exitCode));
                    }
                    recurrencePeriod = 0;
                    controller.cleanup(workspace);
                }
            } catch (IOException x) {
                LOGGER.log(Level.FINE, "could not check " + workspace, x);
                ws = null;
            } catch (InterruptedException x) {
                LOGGER.log(Level.FINE, "could not check " + workspace, x);
                ws = null;
            } finally {
                t.set(null); // cancel timer
            }
        }

        @Override public void onResume() {
            super.onResume();
            setupTimer();
        }

        private void setupTimer() {
            recurrencePeriod = MIN_RECURRENCE_PERIOD;
            Timer.get().schedule(this, recurrencePeriod, TimeUnit.MILLISECONDS);
        }

        private static final long serialVersionUID = 1L;

    }

}
