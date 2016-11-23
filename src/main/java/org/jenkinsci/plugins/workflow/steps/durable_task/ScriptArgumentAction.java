package org.jenkinsci.plugins.workflow.steps.durable_task;

import org.jenkinsci.plugins.workflow.actions.PersistentAction;

/**
 * Stores argument info passed into the script so it can be inspected from the flow graph
 */
public class ScriptArgumentAction implements PersistentAction {
    private static final String actionName = "DurableTaskScript";
    private final String param;

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

    public String getParam() {
        return this.param;
    }

    public ScriptArgumentAction(String param) {
        this.param = param;
    }
}
