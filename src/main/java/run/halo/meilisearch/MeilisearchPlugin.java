package run.halo.meilisearch;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.SettingFetcher;

/**
 * @author ryanwang
 * @since 2.0.0
 */
@Slf4j
@Component
public class MeilisearchPlugin extends BasePlugin {
    private final SchemeManager schemeManager;

    private final SettingFetcher settingFetcher;

    public MeilisearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
        this.schemeManager = getApplicationContext().getBean(SchemeManager.class);
        this.settingFetcher = getApplicationContext().getBean(SettingFetcher.class);
    }

    @Override
    public void start() {
        Settings settings = settingFetcher.getGroupForObject("settings", Settings.class);
        log.error("settings: {}", settings);
    }

    @Override
    public void stop() {
    }
}
