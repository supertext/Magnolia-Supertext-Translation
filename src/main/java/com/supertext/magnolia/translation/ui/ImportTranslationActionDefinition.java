package com.supertext.magnolia.translation.ui;

import info.magnolia.ui.api.action.ConfiguredActionDefinition;

/**
 * Commit action of the {@code supertext-translation:import} dialog.
 */
public class ImportTranslationActionDefinition extends ConfiguredActionDefinition {

    public ImportTranslationActionDefinition() {
        setImplementationClass(ImportTranslationAction.class);
    }
}
