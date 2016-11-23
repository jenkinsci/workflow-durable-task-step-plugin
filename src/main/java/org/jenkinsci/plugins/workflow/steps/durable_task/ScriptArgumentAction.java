package org.jenkinsci.plugins.workflow.steps.durable_task;

import org.jenkinsci.plugins.workflow.actions.PersistentAction;

import javax.annotation.CheckForNull;

/**
 * Stores argument info passed into the script so it can be inspected from the flow graph
 */
public class ScriptArgumentAction implements PersistentAction {
    private static final String actionName = "DurableTaskScript";
    private final String script;

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return actionName;
    }

    @Override
    public String getUrlName() {
        return null;
    }

    @CheckForNull
    public String getScript() {
        return this.script;
    }

    public ScriptArgumentAction(String script) {
        this.script = script;
    }
}
