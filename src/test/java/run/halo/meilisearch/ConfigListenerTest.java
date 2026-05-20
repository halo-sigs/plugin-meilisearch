package run.halo.meilisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

@ExtendWith(MockitoExtension.class)
class ConfigListenerTest {

    @Mock
    ExtensionClient client;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    ControllerBuilder controllerBuilder;

    @Mock
    Controller controller;

    @Test
    void reconcileShouldIgnoreMissingConfigMap() {
        var listener = new ConfigListener(client, eventPublisher);
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.empty());

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreUnexpectedConfigMapRequest() {
        var listener = new ConfigListener(client, eventPublisher);

        var result = listener.reconcile(new Reconciler.Request("another-plugin-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(client, eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreConfigMapWithoutData() {
        var listener = new ConfigListener(client, eventPublisher);
        var configMap = new ConfigMap();
        configMap.setData(null);
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreConfigMapWithoutBasicGroup() {
        var listener = new ConfigListener(client, eventPublisher);
        var configMap = new ConfigMap();
        configMap.setData(Map.of("other", "{}"));
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreDeletedConfigMap() {
        var listener = new ConfigListener(client, eventPublisher);
        var metadata = new Metadata();
        metadata.setDeletionTimestamp(Instant.now());
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata);
        configMap.setData(Map.of("basic", """
            {
              "host": "http://meilisearch:7700",
              "masterKey": "secret",
              "indexName": "halo"
            }
            """));
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreMalformedConfig() {
        var listener = new ConfigListener(client, eventPublisher);
        var configMap = new ConfigMap();
        configMap.setData(Map.of("basic", "{"));
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldIgnoreIncompleteConfig() {
        var listener = new ConfigListener(client, eventPublisher);
        var configMap = new ConfigMap();
        configMap.setData(Map.of("basic", """
            {
              "host": "",
              "masterKey": "secret",
              "indexName": "halo"
            }
            """));
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconcileShouldPublishConfigUpdatedEvent() {
        var listener = new ConfigListener(client, eventPublisher);
        var configMap = new ConfigMap();
        configMap.setData(Map.of("basic", """
            {
              "host": "http://meilisearch:7700",
              "masterKey": "secret",
              "indexName": "halo"
            }
            """));
        when(client.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));

        var result = listener.reconcile(new Reconciler.Request("meilisearch-engine-config"));

        assertThat(result).isEqualTo(Reconciler.Result.doNotRetry());
        var eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(ConfigUpdatedEvent.class);
        var properties =
            ((ConfigUpdatedEvent) eventCaptor.getValue()).getMeilisearchProperties();
        assertThat(properties.getHost()).isEqualTo("http://meilisearch:7700");
        assertThat(properties.getMasterKey()).isEqualTo("secret");
        assertThat(properties.getIndexName()).isEqualTo("halo");
    }

    @Test
    void setupWithShouldSyncExistingConfigMapOnStart() {
        var listener = new ConfigListener(client, eventPublisher);
        when(controllerBuilder.extension(any(ConfigMap.class))).thenReturn(controllerBuilder);
        when(controllerBuilder.syncAllOnStart(anyBoolean())).thenReturn(controllerBuilder);
        when(controllerBuilder.onAddMatcher(any())).thenReturn(controllerBuilder);
        when(controllerBuilder.onDeleteMatcher(any())).thenReturn(controllerBuilder);
        when(controllerBuilder.onUpdateMatcher(any())).thenReturn(controllerBuilder);
        when(controllerBuilder.build()).thenReturn(controller);

        var result = listener.setupWith(controllerBuilder);

        assertThat(result).isSameAs(controller);
        verify(controllerBuilder).syncAllOnStart(true);
    }
}
