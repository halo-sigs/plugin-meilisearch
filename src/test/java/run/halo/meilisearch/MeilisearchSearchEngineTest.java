package run.halo.meilisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.SearchResult;
import com.meilisearch.sdk.model.TaskInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.plugin.event.PluginStartedEvent;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchOption;
import run.halo.app.search.event.HaloDocumentRebuildRequestEvent;

@ExtendWith(MockitoExtension.class)
class MeilisearchSearchEngineTest {

    private static final String[] SEARCH_ATTRIBUTES = {"title", "description", "content"};
    private static final String[] FILTERABLE_ATTRIBUTES = {
        "published", "recycled", "exposed", "type", "ownerName", "categories", "tags"
    };
    private static final String[] DISPLAYED_ATTRIBUTES = {
        "id", "metadataName", "title", "annotations", "description", "categories", "tags",
        "published", "recycled", "exposed", "ownerName", "creationTimestamp",
        "updateTimestamp", "permalink", "type", "content"
    };

    @Mock
    ExtensionClient extensionClient;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    MeilisearchSearchEngine.ClientFactory clientFactory;

    @Mock
    ScheduledExecutorService retryExecutor;

    @Mock
    ScheduledFuture<Object> retryFuture;

    @Mock
    Client meilisearchClient;

    @Mock
    Client retryMeilisearchClient;

    @Mock
    Index index;

    @Mock
    Index retryIndex;

    @Mock
    Index legacyIndex;

    @Mock
    Index recreatedIndex;

    @Mock
    TaskInfo taskInfo;

    @Mock
    TaskInfo retryTaskInfo;

    @Mock
    TaskInfo deleteIndexTaskInfo;

    @Mock
    TaskInfo createIndexTaskInfo;

    @Mock
    SearchResult searchResult;

    @Test
    void configUpdatedEventShouldInitializeIndexSettingsBeforeBecomingAvailable()
        throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        var searchableAttributesCaptor = ArgumentCaptor.forClass(String[].class);
        var filterableAttributesCaptor = ArgumentCaptor.forClass(String[].class);
        var displayedAttributesCaptor = ArgumentCaptor.forClass(String[].class);
        verify(index).updateSearchableAttributesSettings(searchableAttributesCaptor.capture());
        verify(index).updateFilterableAttributesSettings(filterableAttributesCaptor.capture());
        verify(index).updateDisplayedAttributesSettings(displayedAttributesCaptor.capture());
        assertThat(searchableAttributesCaptor.getValue())
            .containsExactly("title", "description", "content");
        assertThat(filterableAttributesCaptor.getValue())
            .containsExactly("published", "recycled", "exposed", "type", "ownerName",
                "categories", "tags");
        assertThat(displayedAttributesCaptor.getValue())
            .contains("ownerName", "content", "permalink");
        verify(index, times(3)).waitForTask(123, 60_000, 100);
        verify(retryExecutor, never()).schedule(any(Runnable.class), anyLong(),
            any(TimeUnit.class));
        verify(eventPublisher, never()).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void blankConfigurationShouldBeIgnoredWithoutRetrying() {
        var engine = newEngine();
        var properties = properties();
        properties.setHost(" ");

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties));

        assertThat(engine.available()).isFalse();
        verifyNoInteractions(clientFactory, retryExecutor, eventPublisher);
    }

    @Test
    void blankConfigurationShouldNotDisableExistingEngine() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var properties = properties();
        properties.setHost(" ");

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties));

        assertThat(engine.available()).isTrue();
        verify(clientFactory, times(1)).create(any(Config.class));
        verify(retryExecutor, never()).schedule(any(Runnable.class), anyLong(),
            any(TimeUnit.class));
    }

    @Test
    void sameConfigurationShouldNotReinitializeClient() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(clientFactory, times(1)).create(any(Config.class));
        verify(index, times(1)).updateSearchableAttributesSettings(any(String[].class));
    }

    @Test
    void initializationFailureShouldRetryAndRebuildAfterRecovery() throws Exception {
        when(clientFactory.create(any(Config.class)))
            .thenReturn(meilisearchClient, retryMeilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo")).thenReturn(index);
        when(index.updateSearchableAttributesSettings(any(String[].class)))
            .thenThrow(new RuntimeException("not ready"));
        var retryTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(retryFuture)
            .when(retryExecutor)
            .schedule(retryTaskCaptor.capture(), eq(5L), eq(TimeUnit.SECONDS));
        givenSuccessfulInitialization(retryMeilisearchClient, retryIndex, retryTaskInfo, "halo", 456);
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isFalse();
        verify(retryExecutor).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));

        engine.onApplicationEvent(new PluginStartedEvent(this));
        retryTaskCaptor.getValue().run();

        assertThat(engine.available()).isTrue();
        verify(retryIndex, times(3)).waitForTask(456, 60_000, 100);
        verify(eventPublisher).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void newConfigurationShouldCancelPendingRetryAndIgnoreStaleRetry() throws Exception {
        when(clientFactory.create(any(Config.class)))
            .thenReturn(meilisearchClient, retryMeilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo")).thenReturn(index);
        when(index.updateSearchableAttributesSettings(any(String[].class)))
            .thenThrow(new RuntimeException("not ready"));
        var retryTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(retryFuture)
            .when(retryExecutor)
            .schedule(retryTaskCaptor.capture(), eq(5L), eq(TimeUnit.SECONDS));
        givenSuccessfulInitialization(retryMeilisearchClient, retryIndex, retryTaskInfo,
            "new-index", 456);
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        engine.onApplicationEvent(new ConfigUpdatedEvent(this,
            properties("http://meilisearch-2:7700", "secret-2", "new-index")));

        assertThat(engine.available()).isTrue();
        verify(retryFuture).cancel(false);

        retryTaskCaptor.getValue().run();

        verify(clientFactory, times(2)).create(any(Config.class));
        verify(eventPublisher, never()).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void initializationRetryShouldStopAtRetryLimit() throws Exception {
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo")).thenReturn(index);
        when(index.updateSearchableAttributesSettings(any(String[].class)))
            .thenThrow(new RuntimeException("not ready"));
        var retryTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(retryFuture)
            .when(retryExecutor)
            .schedule(retryTaskCaptor.capture(), eq(5L), eq(TimeUnit.SECONDS));
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        for (var i = 0; i < 24; i++) {
            var retryTasks = retryTaskCaptor.getAllValues();
            retryTasks.get(retryTasks.size() - 1).run();
        }

        assertThat(engine.available()).isFalse();
        verify(retryExecutor, times(24)).schedule(any(Runnable.class), eq(5L),
            eq(TimeUnit.SECONDS));
        verify(clientFactory, times(25)).create(any(Config.class));
    }

    @Test
    void destroyShouldCancelRetryAndIgnoreFurtherUpdates() throws Exception {
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo")).thenReturn(index);
        when(index.updateSearchableAttributesSettings(any(String[].class)))
            .thenThrow(new RuntimeException("not ready"));
        doReturn(retryFuture)
            .when(retryExecutor)
            .schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        engine.destroy();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isFalse();
        verify(retryFuture).cancel(false);
        verify(retryExecutor).shutdownNow();
        verify(clientFactory, times(1)).create(any(Config.class));
    }

    @Test
    void configUpdateShouldRebuildAfterStartupConfigurationWasMissing() throws Exception {
        when(extensionClient.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.empty());
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();

        engine.afterPropertiesSet();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(eventPublisher, never()).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));

        engine.onApplicationEvent(new PluginStartedEvent(this));

        verify(eventPublisher).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void configUpdateShouldRebuildAfterStartupConfigurationWasMalformed() throws Exception {
        var configMap = new ConfigMap();
        configMap.setData(Map.of("basic", "{"));
        when(extensionClient.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.of(configMap));
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();

        engine.afterPropertiesSet();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(eventPublisher, never()).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));

        engine.onApplicationEvent(new PluginStartedEvent(this));

        verify(eventPublisher).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void rebuildEventFailureShouldNotMakeRecoveredEngineUnavailable() throws Exception {
        when(extensionClient.fetch(ConfigMap.class, "meilisearch-engine-config"))
            .thenReturn(Optional.empty());
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        doThrow(new RuntimeException("listener failed"))
            .when(eventPublisher)
            .publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
        var engine = newEngine();

        engine.afterPropertiesSet();
        engine.onApplicationEvent(new PluginStartedEvent(this));
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(retryExecutor, never()).schedule(any(Runnable.class), anyLong(),
            any(TimeUnit.class));
    }

    @Test
    void unavailableSearchShouldReturnEmptyResultWithRequestMetadata() {
        var engine = newEngine();
        var option = searchOption();

        var result = engine.search(option);

        assertThat(result.getKeyword()).isEqualTo("halo");
        assertThat(result.getLimit()).isEqualTo(20);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getHits()).isEmpty();
    }

    @Test
    void searchShouldReturnEmptyResultWhenMeilisearchThrows() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.search(any(SearchRequest.class))).thenThrow(new MeilisearchException("boom"));
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var option = searchOption();

        var result = engine.search(option);

        assertThat(result.getKeyword()).isEqualTo("halo");
        assertThat(result.getLimit()).isEqualTo(20);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getHits()).isEmpty();
    }

    @Test
    void searchShouldReturnEmptyResultWhenUnexpectedSearchErrorOccurs() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.search(any(SearchRequest.class))).thenThrow(new RuntimeException("boom"));
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var option = searchOption();

        var result = engine.search(option);

        assertThat(result.getKeyword()).isEqualTo("halo");
        assertThat(result.getTotal()).isZero();
        assertThat(result.getHits()).isEmpty();
    }

    @Test
    void searchShouldRecoverSettingsAndRetryWhenSearchAttributesAreInvalid() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.search(any(SearchRequest.class)))
            .thenThrow(new MeilisearchException("invalid_search_attributes_to_search_on"))
            .thenReturn(searchResult);
        when(searchResult.getEstimatedTotalHits()).thenReturn(1);
        when(searchResult.getProcessingTimeMs()).thenReturn(7);
        when(searchResult.getHits()).thenReturn(new ArrayList<>(List.of(hit())));
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var option = searchOption();

        var result = engine.search(option);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getHits()).hasSize(1);
        verify(index, times(2)).updateSearchableAttributesSettings(any(String[].class));
        verify(index, times(2)).search(any(SearchRequest.class));
        assertThat(engine.available()).isTrue();
    }

    @Test
    void initializationShouldRetryWhenSettingsRemainInvalidAfterUpdate() throws Exception {
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo")).thenReturn(index);
        givenSuccessfulSettingsUpdate(index, taskInfo, 123);
        when(index.getSearchableAttributesSettings()).thenReturn(new String[]{});
        doReturn(retryFuture)
            .when(retryExecutor)
            .schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isFalse();
        verify(retryExecutor).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test
    void searchShouldBuildAllSupportedFilters() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(searchResult.getEstimatedTotalHits()).thenReturn(1);
        when(searchResult.getProcessingTimeMs()).thenReturn(7);
        when(searchResult.getHits()).thenReturn(new ArrayList<>(List.of(hit())));
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var option = searchOption();
        option.setFilterPublished(true);
        option.setFilterRecycled(false);
        option.setFilterExposed(true);
        option.setIncludeTypes(values("", "post.content.halo.run", "post.content.halo.run", null));
        option.setIncludeOwnerNames(values(null, " admin ", " "));
        option.setIncludeCategoryNames(values("category-a", "", null, "category-b"));
        option.setIncludeTagNames(values("tag-a", "tag-a", null));

        var result = engine.search(option);

        assertThat(result.getTotal()).isEqualTo(1);
        var requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(index).search(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFilter())
            .containsExactly("recycled = false AND exposed = true AND published = true"
                + " AND (type = 'post.content.halo.run') AND (ownerName = 'admin')"
                + " AND (tags = 'tag-a') AND (categories = 'category-a' AND categories = 'category-b')");
    }

    @Test
    void searchShouldSkipNullAndMalformedHits() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(searchResult.getEstimatedTotalHits()).thenReturn(3);
        when(searchResult.getProcessingTimeMs()).thenReturn(7);
        var hits = new ArrayList<HashMap<String, Object>>();
        hits.add(null);
        hits.add(malformedHit());
        hits.add(hit());
        when(searchResult.getHits()).thenReturn(hits);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        var result = engine.search(searchOption());

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getHits()).hasSize(1);
        assertThat(result.getHits().getFirst().getMetadataName()).isEqualTo("post-name");
    }

    @Test
    void addOrUpdateShouldStripHtmlBeforeIndexing() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));
        var document = document();
        document.setDescription("<p>Hello <strong>Halo</strong></p>");
        document.setContent("<article>Search <em>content</em></article>");

        engine.addOrUpdate(List.of(document));

        var documentsCaptor = ArgumentCaptor.forClass(String.class);
        verify(index).addDocuments(documentsCaptor.capture(), eq("id"));
        assertThat(documentsCaptor.getValue()).contains("Hello Halo", "Search content");
        assertThat(documentsCaptor.getValue()).doesNotContain("<p>", "<strong>", "<article>", "<em>");
    }

    @Test
    void unavailableWriteOperationsShouldNotTouchMeilisearch() {
        var engine = newEngine();

        engine.addOrUpdate(List.of(document()));
        engine.deleteDocument(List.of("post.content.halo.run-post-a"));
        engine.deleteAll();

        verifyNoInteractions(clientFactory, index);
    }

    @Test
    void addOrUpdateShouldIgnoreMissingOrEmptyDocuments() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        engine.addOrUpdate(null);
        engine.addOrUpdate(List.of());
        var documentsWithNull = new ArrayList<HaloDocument>();
        documentsWithNull.add(null);
        engine.addOrUpdate(documentsWithNull);

        verify(index, never()).addDocuments(anyString(), eq("id"));
    }

    @Test
    void deleteDocumentShouldUseHaloDocumentIds() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        engine.deleteDocument(List.of(
            "post.content.halo.run-post-name",
            "singlepage.content.halo.run-page-name"
        ));

        verify(index).deleteDocuments(List.of(
            "post.content.halo.run-post-name",
            "singlepage.content.halo.run-page-name"
        ));
    }

    @Test
    void deleteDocumentShouldIgnoreMissingDocumentIds() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        var docIds = new ArrayList<String>();
        docIds.add(null);
        docIds.add("");
        docIds.add(" ");
        engine.deleteDocument(docIds);

        verify(index, never()).deleteDocuments(any());
        verify(index, never()).deleteAllDocuments();
    }

    @Test
    void deleteDocumentShouldDeleteAllWhenDocumentIdsAreMissing() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        engine.deleteDocument(null);

        verify(index).deleteAllDocuments();
        verify(index, never()).deleteDocuments(any());
    }

    @Test
    void deleteOperationsShouldSwallowMeilisearchExceptions() throws Exception {
        givenSuccessfulInitialization(meilisearchClient, index, taskInfo, "halo", 123);
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(index.deleteDocuments(any())).thenThrow(new MeilisearchException("delete failed"));
        when(index.deleteAllDocuments()).thenThrow(new MeilisearchException("delete all failed"));
        var engine = newEngine();
        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        engine.deleteDocument(List.of("post.content.halo.run-post-name"));
        engine.deleteAll();

        assertThat(engine.available()).isTrue();
    }

    @Test
    void initializationShouldCreateMissingIndexWithHaloDocumentIdPrimaryKey() throws Exception {
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index);
        when(meilisearchClient.getIndex("halo"))
            .thenThrow(new MeilisearchException("index not found"));
        when(meilisearchClient.createIndex("halo", "id")).thenReturn(createIndexTaskInfo);
        when(createIndexTaskInfo.getTaskUid()).thenReturn(321);
        givenSuccessfulSettingsInitialization(index, taskInfo, 123);
        var engine = newEngine();
        engine.onApplicationEvent(new PluginStartedEvent(this));

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(meilisearchClient).waitForTask(321);
        verify(eventPublisher).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    @Test
    void initializationShouldRecreateLegacyMetadataNamePrimaryKeyIndex() throws Exception {
        when(clientFactory.create(any(Config.class))).thenReturn(meilisearchClient);
        when(meilisearchClient.index("halo")).thenReturn(index, recreatedIndex);
        when(meilisearchClient.getIndex("halo")).thenReturn(legacyIndex);
        when(legacyIndex.getPrimaryKey()).thenReturn("metadataName");
        when(meilisearchClient.deleteIndex("halo")).thenReturn(deleteIndexTaskInfo);
        when(deleteIndexTaskInfo.getTaskUid()).thenReturn(111);
        when(meilisearchClient.createIndex("halo", "id")).thenReturn(createIndexTaskInfo);
        when(createIndexTaskInfo.getTaskUid()).thenReturn(222);
        givenSuccessfulSettingsInitialization(recreatedIndex, taskInfo, 333);
        var engine = newEngine();

        engine.onApplicationEvent(new ConfigUpdatedEvent(this, properties()));

        assertThat(engine.available()).isTrue();
        verify(meilisearchClient).waitForTask(111);
        verify(meilisearchClient).waitForTask(222);
        verify(recreatedIndex, times(3)).waitForTask(333, 60_000, 100);
        verify(eventPublisher, never()).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));

        engine.onApplicationEvent(new PluginStartedEvent(this));

        verify(eventPublisher).publishEvent(isA(HaloDocumentRebuildRequestEvent.class));
    }

    private void givenSuccessfulInitialization(Client client, Index index, TaskInfo taskInfo,
        String indexName, int taskUid) throws Exception {
        when(client.index(indexName)).thenReturn(index);
        when(client.getIndex(indexName)).thenReturn(index);
        givenSuccessfulSettingsInitialization(index, taskInfo, taskUid);
    }

    private void givenSuccessfulSettingsInitialization(Index index, TaskInfo taskInfo, int taskUid)
        throws Exception {
        givenSuccessfulSettingsUpdate(index, taskInfo, taskUid);
        when(index.getSearchableAttributesSettings()).thenReturn(SEARCH_ATTRIBUTES);
        when(index.getFilterableAttributesSettings()).thenReturn(FILTERABLE_ATTRIBUTES);
        when(index.getDisplayedAttributesSettings()).thenReturn(DISPLAYED_ATTRIBUTES);
    }

    private void givenSuccessfulSettingsUpdate(Index index, TaskInfo taskInfo, int taskUid)
        throws Exception {
        when(index.updateSearchableAttributesSettings(any(String[].class))).thenReturn(taskInfo);
        when(index.updateFilterableAttributesSettings(any(String[].class))).thenReturn(taskInfo);
        when(index.updateDisplayedAttributesSettings(any(String[].class))).thenReturn(taskInfo);
        when(taskInfo.getTaskUid()).thenReturn(taskUid);
    }

    private MeilisearchSearchEngine newEngine() {
        return new MeilisearchSearchEngine(extensionClient, eventPublisher, clientFactory,
            retryExecutor);
    }

    private MeilisearchProperties properties() {
        return properties("http://meilisearch:7700", "secret", "halo");
    }

    private MeilisearchProperties properties(String host, String masterKey, String indexName) {
        var properties = new MeilisearchProperties();
        properties.setHost(host);
        properties.setMasterKey(masterKey);
        properties.setIndexName(indexName);
        return properties;
    }

    private SearchOption searchOption() {
        var option = new SearchOption();
        option.setKeyword("halo");
        option.setLimit(20);
        option.setHighlightPreTag("<mark>");
        option.setHighlightPostTag("</mark>");
        return option;
    }

    private List<String> values(String... values) {
        var result = new ArrayList<String>();
        for (var value : values) {
            result.add(value);
        }
        return result;
    }

    private HashMap<String, Object> hit() {
        var hit = new HashMap<String, Object>();
        hit.put("id", "post.content.halo.run-post-name");
        hit.put("metadataName", "post-name");
        hit.put("title", "Halo");
        hit.put("content", "Search content");
        hit.put("description", "Description");
        hit.put("published", true);
        hit.put("recycled", false);
        hit.put("exposed", true);
        hit.put("ownerName", "admin");
        hit.put("permalink", "/archives/post-name");
        hit.put("type", "post.content.halo.run");
        return hit;
    }

    private HashMap<String, Object> malformedHit() {
        return new HashMap<>() {
            @Override
            public Object get(Object key) {
                throw new IllegalArgumentException("malformed hit");
            }
        };
    }

    private HaloDocument document() {
        var document = new HaloDocument();
        document.setId("post.content.halo.run-post-name");
        document.setMetadataName("post-name");
        document.setTitle("Halo");
        document.setContent("Search content");
        document.setDescription("Description");
        document.setPublished(true);
        document.setRecycled(false);
        document.setExposed(true);
        document.setOwnerName("admin");
        document.setPermalink("/archives/post-name");
        document.setType("post.content.halo.run");
        return document;
    }
}
