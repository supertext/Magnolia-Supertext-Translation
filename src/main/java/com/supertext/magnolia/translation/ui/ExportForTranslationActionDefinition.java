package com.supertext.magnolia.translation.ui;

import info.magnolia.ui.api.action.ConfiguredActionDefinition;

/**
 * Commit action of the {@code supertext-translation:export} dialog.
 */
public class ExportForTranslationActionDefinition extends ConfiguredActionDefinition {

    public ExportForTranslationActionDefinition() {
        setImplementationClass(ExportForTranslationAction.class);
    }
}
