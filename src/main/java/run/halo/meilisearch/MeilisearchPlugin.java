package run.halo.meilisearch;

import org.pf4j.PluginWrapper;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;

/**
 * @author ryanwang
 * @since 2.0.0
 */
public class MeilisearchPlugin extends BasePlugin {
    private final SchemeManager schemeManager;

    public MeilisearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
        this.schemeManager = getApplicationContext().getBean(SchemeManager.class);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
