package com.supertext.magnolia.translation.setup;

import javax.jcr.RepositoryException;

import info.magnolia.module.InstallContext;
import info.magnolia.module.delta.AbstractRepositoryTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.supertext.magnolia.translation.ui.settings.SettingsStore;

/**
 * Moves the <em>Language codes</em> text of 0.1.x ({@code languageMapping}, {@code de=de-CH} per line) into the
 * {@code languageCodes} node the combo boxes use, so nothing configured is lost. Does nothing when there is no text,
 * so it runs at every start (like {@link RegisterPingTask}) and needs no update delta.
 */
public class MigrateLanguageCodesTask extends AbstractRepositoryTask {

    private static final Logger log = LoggerFactory.getLogger(MigrateLanguageCodesTask.class);

    public MigrateLanguageCodesTask() {
        super("Supertext language codes", "Moves the Supertext language codes text of version 0.1.x into one entry per language.");
    }

    @Override
    protected void doExecute(InstallContext ctx) throws RepositoryException {
        int moved = SettingsStore.migrateLegacyMapping(ctx.getConfigJCRSession());
        if (moved > 0) {
            log.info("Supertext: moved {} language code(s) from the old text setting to {}/{}", moved,
                    SettingsStore.CONFIG_PATH, SettingsStore.LANGUAGE_CODES);
        }
    }
}
