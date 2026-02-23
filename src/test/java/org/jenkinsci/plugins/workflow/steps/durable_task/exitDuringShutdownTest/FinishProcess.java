/*
 * The MIT License
 *
 * Copyright 2025 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.steps.durable_task.exitDuringShutdownTest;

import hudson.init.Terminator;
import java.nio.file.Files;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;

public class FinishProcess {

    private static final Logger LOGGER = Logger.getLogger(FinishProcess.class.getName());

    @Terminator(requires = FlowExecutionList.EXECUTIONS_SUSPENDED)
    public static void run() throws Exception {
        if (Jenkins.get().getPluginManager().getPlugin("ExitDuringShutdownTest") == null) {
            return;
        }
        var f = Jenkins.get().getRootDir().toPath().resolve("f");
        LOGGER.info(() -> "Touching " + f);
        Files.writeString(f, "go");
        Thread.sleep(1_000);
        LOGGER.info("done, and slept a bit too");
    }
    private FinishProcess() {
    }

}
