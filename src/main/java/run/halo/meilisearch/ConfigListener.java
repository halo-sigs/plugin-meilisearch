package run.halo.meilisearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.DefaultExtensionMatcher;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.index.query.QueryFactory;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.utils.JsonUtils;

@Slf4j
@Component
public class ConfigListener implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final ApplicationEventPublisher eventPublisher;

    public ConfigListener(ExtensionClient client, ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Result reconcile(Request request) {
        return client.fetch(ConfigMap.class, request.name())
            .map(configMap -> {
                if (ExtensionUtil.isDeleted(configMap)) {
                    return Result.doNotRetry();
                }

                var data = configMap.getData();
                if (data.containsKey("basic")) {
                    var json = data.get("basic");
                    try {
                        var meilisearchProperties =
                            JsonUtils.mapper().readValue(json, MeilisearchProperties.class);
                        eventPublisher.publishEvent(
                            new ConfigUpdatedEvent(this, meilisearchProperties)
                        );
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse meilisearch properties", e);
                    }
                }
                return Result.doNotRetry();
            })
            .orElseGet(Result::doNotRetry);
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        var configMap = new ConfigMap();
        var matcher = DefaultExtensionMatcher.builder(client, configMap.groupVersionKind())
            .fieldSelector(FieldSelector.of(
                QueryFactory.equal("metadata.name", "meilisearch-engine-config")))
            .build();
        return builder
            .extension(configMap)
            .syncAllOnStart(false)
            .onAddMatcher(matcher)
            .onDeleteMatcher(matcher)
            .onUpdateMatcher(matcher)
            .build();
    }
}