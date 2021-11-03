package org.jenkinsci.plugins.workflow.steps.durable_task;

import hudson.Extension;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.PowershellScript;
import org.kohsuke.stapler.DataBoundConstructor;

public class PowerShellCoreScriptStep extends DurableTaskStep {

    private final String script;

    @DataBoundConstructor
    public PowerShellCoreScriptStep(String script) {
        if (script == null) {
            throw new IllegalArgumentException();
        }
        this.script = script;
    }

    public String getScript() {
        return script;
    }

    @Override protected DurableTask task() {
        PowershellScript powershellScript = new PowershellScript(script);
        powershellScript.setPowershellBinary("pwsh");
        return powershellScript;
    }

    @Extension
    public static final class DescriptorImpl extends DurableTaskStepDescriptor {

        @Override public String getDisplayName() {
            return "PowerShell Core Script";
        }

        @Override public String getFunctionName() {
            return "pwsh";
        }

    }
}
