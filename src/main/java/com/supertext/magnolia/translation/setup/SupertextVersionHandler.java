package com.supertext.magnolia.translation.setup;

import java.util.ArrayList;
import java.util.List;

import info.magnolia.module.DefaultModuleVersionHandler;
import info.magnolia.module.InstallContext;
import info.magnolia.module.delta.Task;

/**
 * Install and update tasks. The ping route is set up at every start ({@link RegisterPingTask} only adds what is
 * missing), so installations updated from 0.1.0 get it without an update delta; the same for the move of the
 * 0.1.x language codes text into one entry per language ({@link MigrateLanguageCodesTask}).
 */
public class SupertextVersionHandler extends DefaultModuleVersionHandler {

    @Override
    protected List<Task> getStartupTasks(InstallContext installContext) {
        List<Task> tasks = new ArrayList<>(super.getStartupTasks(installContext));
        tasks.add(new RegisterPingTask());
        tasks.add(new MigrateLanguageCodesTask());
        return tasks;
    }
}
