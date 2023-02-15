/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.OfflineCause;
import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.support.pickles.FilePathPickle;

/**
 * Analogue of {@link FilePathPickle}.
 * Allows a step body to save a representation of a workspace
 * without forcing a particular {@link FilePath#getChannel} to be used the whole time.
 */
@Extension(ordinal = 100) public final class FilePathDynamicContext extends DynamicContext.Typed<FilePath> {

    private static final Logger LOGGER = Logger.getLogger(FilePathDynamicContext.class.getName());

    @NonNull
    @Override protected Class<FilePath> type() {
        return FilePath.class;
    }

    @Override protected FilePath get(DelegatedContext context) throws IOException, InterruptedException {
        FilePathRepresentation r = context.get(FilePathRepresentation.class);
        if (r == null) {
            return null;
        }
        ExecutorStepDynamicContext esdc = context.get(ExecutorStepDynamicContext.class);
        LOGGER.fine(() -> "ESDC=" + esdc + " FPR=" + r);
        if (esdc != null) {
            if (esdc.depth > r.depth) {
                LOGGER.fine(() -> "skipping " + r.path + "@" + r.slave + " since at depth " + r.depth + " it is shallower than " + esdc.node + "@" + esdc.path + " at depth " + esdc.depth);
                return null;
            } else {
                LOGGER.fine(() -> "not skipping " + r.path + "@" + r.slave + " since at depth " + r.depth + " it is deeper than " + esdc.node + "@" + esdc.path + " at depth " + esdc.depth);
            }
        }
        FilePath f = FilePathUtils.find(r.slave, r.path);
        if (f != null) {
            LOGGER.log(Level.FINE, "serving {0}:{1}", new Object[] {r.slave, r.path});
        } else {
            AgentOfflineException e = new AgentOfflineException("Unable to create live FilePath for " + r.slave);
            Computer c = Jenkins.get().getComputer(r.slave);
            if (c != null) {
                for (Computer.TerminationRequest tr : c.getTerminatedBy()) {
                    e.addSuppressed(tr);
                }
            }
            TaskListener listener = context.get(TaskListener.class);
            if (listener != null) {
                OfflineCause oc = c.getOfflineCause();
                if (oc != null) {
                    listener.getLogger().println(c.getDisplayName() + " was marked offline: " + oc);
                }
            }
            throw e;
        }
        return f;
    }

    /**
     * @deprecated use {@link #createContextualObject(FilePath, FlowNode)}
     */
    @Deprecated
    public static Object createContextualObject(FilePath f) {
        return new FilePathRepresentation(FilePathUtils.getNodeName(f), f.getRemote(), 0);
    }

    /**
     * @see BodyInvoker#withContext
     */
    public static Object createContextualObject(@NonNull FilePath f, @NonNull FlowNode n) {
        return new FilePathRepresentation(FilePathUtils.getNodeName(f), f.getRemote(), depthOf(n));
    }

    static int depthOf(@NonNull FlowNode n) {
        return n.getEnclosingBlocks().size();
    }

    static final class FilePathRepresentation implements Serializable {

        private static final long serialVersionUID = 1;

        private final String slave;
        private final String path;
        private final int depth;

        FilePathRepresentation(String slave, String path, int depth) {
            this.slave = slave;
            this.path = path;
            this.depth = depth;
        }

        @Override public String toString() {
            return "FilePathRepresentation[" + path + "@" + slave + "]";
        }

    }

}
