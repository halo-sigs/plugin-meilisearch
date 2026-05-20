package run.halo.meilisearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.IndexStats;
import com.meilisearch.sdk.model.SearchResult;
import com.meilisearch.sdk.model.Searchable;
import com.meilisearch.sdk.model.Settings;
import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;
import com.meilisearch.sdk.model.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.stream.Streams;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.plugin.event.PluginStartedEvent;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchEngine;
import run.halo.app.search.SearchOption;
import run.halo.app.search.event.HaloDocumentRebuildRequestEvent;

@Slf4j
@Component
public class MeilisearchSearchEngine implements SearchEngine, DisposableBean,
    InitializingBean {

    private static final String[] HIGHLIGHT_ATTRIBUTES =
        {"title", "description", "content", "categories", "tags"};
    private static final String[] SEARCH_ATTRIBUTES = {"title", "description", "content"};
    private static final String[] CROP_ATTRIBUTES = {"description", "content"};
    private static final String[] FILTERABLE_ATTRIBUTES = {
        "published", "recycled", "exposed", "type", "ownerName", "categories", "tags"
    };
    private static final String[] DISPLAYED_ATTRIBUTES = {
        "id", "metadataName", "title", "annotations", "description", "categories", "tags",
        "published", "recycled", "exposed", "ownerName", "creationTimestamp",
        "updateTimestamp", "permalink", "type", "content"
    };
    private static final String DOCUMENT_PRIMARY_KEY = "meilisearchId";
    private static final int TASK_WAIT_TIMEOUT_MS = 60_000;
    private static final int TASK_WAIT_INTERVAL_MS = 100;
    private static final int RETRY_DELAY_SECONDS = 5;
    private static final int MAX_RETRY_ATTEMPTS = 24;

    private final ExtensionClient client;
    private final ApplicationEventPublisher eventPublisher;
    private final ClientFactory clientFactory;
    private final ScheduledExecutorService retryExecutor;

    private Client meilisearchClient;
    private Index index;
    private volatile boolean available = false;
    private volatile boolean disposed = false;
    private ScheduledFuture<?> retryFuture;
    private int retryAttempts = 0;
    private volatile String currentHost;
    private volatile String currentApiKey;
    private volatile String currentIndexName;
    private volatile String pendingHost;
    private volatile String pendingApiKey;
    private volatile String pendingIndexName;
    private boolean rebuildOnNextSuccessfulInitialization = false;
    private boolean pluginStarted = false;
    private boolean rebuildPending = false;

    @Autowired
    public MeilisearchSearchEngine(ExtensionClient client,
        ApplicationEventPublisher eventPublisher) {
        this(client, eventPublisher, config -> new Client(config),
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "meilisearch-initialization-retry");
                thread.setDaemon(true);
                return thread;
            }));
    }

    MeilisearchSearchEngine(ExtensionClient client,
        ApplicationEventPublisher eventPublisher,
        ClientFactory clientFactory,
        ScheduledExecutorService retryExecutor) {
        this.client = client;
        this.eventPublisher = eventPublisher;
        this.clientFactory = clientFactory;
        this.retryExecutor = retryExecutor;
    }

    private synchronized void refresh(String host, String apiKey, String indexName) {
        if (this.disposed) {
            return;
        }
        if (!hasUsableConfiguration(host, indexName)) {
            logIncompleteConfiguration(host, indexName);
            return;
        }
        cancelScheduledRetry();
        this.retryAttempts = 0;
        refresh(host, apiKey, indexName, true);
    }

    private void refresh(String host, String apiKey, String indexName, boolean scheduleRetry) {
        if (this.available
            && Objects.equals(this.currentHost, host)
            && Objects.equals(this.currentApiKey, apiKey)
            && Objects.equals(this.currentIndexName, indexName)) {
            return;
        }

        resetState();

        try {
            this.meilisearchClient = clientFactory.create(new Config(host, apiKey));
            this.index = this.meilisearchClient.index(indexName);

            ensureIndexPrimaryKey(indexName);
            initializeIndexSettings();

            var recoveredFromRetry = this.retryAttempts > 0;
            var shouldRebuild = recoveredFromRetry || this.rebuildOnNextSuccessfulInitialization;
            this.available = true;
            this.currentHost = host;
            this.currentApiKey = apiKey;
            this.currentIndexName = indexName;
            this.retryAttempts = 0;
            this.rebuildOnNextSuccessfulInitialization = false;
            clearPendingConfig();
            log.info("Meilisearch client initialized successfully, index: {}", indexName);
            if (shouldRebuild) {
                requestRebuild();
            }
        } catch (MeilisearchException e) {
            log.error("Failed to initialize Meilisearch client", e);
            this.available = false;
            if (scheduleRetry) {
                scheduleRetry(host, apiKey, indexName);
            }
        } catch (Exception e) {
            log.error("Unexpected error during Meilisearch initialization", e);
            this.available = false;
            if (scheduleRetry) {
                scheduleRetry(host, apiKey, indexName);
            }
        }
    }

    private boolean hasUsableConfiguration(String host, String indexName) {
        return host != null && !host.isBlank()
            && indexName != null && !indexName.isBlank();
    }

    private void logIncompleteConfiguration(String host, String indexName) {
        if (host == null || host.isBlank()) {
            log.warn("Meilisearch host is not configured");
            return;
        }
        log.warn("Meilisearch index name is not configured");
    }

    private void requestRebuild() {
        if (!this.pluginStarted) {
            this.rebuildPending = true;
            log.info("Deferring search index rebuild until Meilisearch plugin is fully started");
            return;
        }
        this.rebuildPending = false;
        publishRebuildRequest();
    }

    private void publishRebuildRequest() {
        try {
            log.info("Requesting search index rebuild after Meilisearch initialization recovery");
            this.eventPublisher.publishEvent(new HaloDocumentRebuildRequestEvent(this));
        } catch (Exception e) {
            log.warn("Failed to publish search index rebuild request", e);
        }
    }

    private void clearPendingConfig() {
        this.pendingHost = null;
        this.pendingApiKey = null;
        this.pendingIndexName = null;
    }

    private void scheduleRetry(String host, String apiKey, String indexName) {
        if (this.disposed) {
            return;
        }
        if (this.retryAttempts >= MAX_RETRY_ATTEMPTS) {
            log.warn("Meilisearch initialization retry limit reached, index: {}", indexName);
            return;
        }

        this.retryAttempts++;
        this.pendingHost = host;
        this.pendingApiKey = apiKey;
        this.pendingIndexName = indexName;
        this.retryFuture = retryExecutor.schedule(() -> retry(host, apiKey, indexName),
            RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        log.info("Scheduled Meilisearch initialization retry {}/{} in {}s, index: {}",
            this.retryAttempts, MAX_RETRY_ATTEMPTS, RETRY_DELAY_SECONDS, indexName);
    }

    private synchronized void retry(String host, String apiKey, String indexName) {
        this.retryFuture = null;
        if (this.disposed || !isPendingConfig(host, apiKey, indexName)) {
            return;
        }

        log.info("Retrying Meilisearch initialization {}/{}, index: {}",
            this.retryAttempts, MAX_RETRY_ATTEMPTS, indexName);
        refresh(host, apiKey, indexName, true);
    }

    private boolean isPendingConfig(String host, String apiKey, String indexName) {
        return Objects.equals(this.pendingHost, host)
            && Objects.equals(this.pendingApiKey, apiKey)
            && Objects.equals(this.pendingIndexName, indexName);
    }

    private void cancelScheduledRetry() {
        if (this.retryFuture != null) {
            this.retryFuture.cancel(false);
            this.retryFuture = null;
        }
        clearPendingConfig();
    }

    private void resetState() {
        this.available = false;
        this.meilisearchClient = null;
        this.index = null;
    }

    private void initializeIndexSettings() throws MeilisearchException {
        updateIndexSettings();
        validateIndexSettings();
    }

    private void updateIndexSettings() throws MeilisearchException {
        var settings = new Settings()
            .setSearchableAttributes(SEARCH_ATTRIBUTES)
            .setFilterableAttributes(FILTERABLE_ATTRIBUTES)
            .setDisplayedAttributes(DISPLAYED_ATTRIBUTES);
        waitForTaskSucceeded(this.index.updateSettings(settings), "update index settings");
    }

    private void waitForTaskSucceeded(TaskInfo taskInfo, String operation)
        throws MeilisearchException {
        if (taskInfo == null) {
            throw new MeilisearchException("Meilisearch task was not created while trying to "
                + operation);
        }
        var taskUid = taskInfo.getTaskUid();
        this.index.waitForTask(taskUid, TASK_WAIT_TIMEOUT_MS, TASK_WAIT_INTERVAL_MS);

        var task = this.index.getTask(taskUid);
        if (task == null || task.getStatus() != TaskStatus.SUCCEEDED) {
            throw new MeilisearchException("Meilisearch task failed while trying to "
                + operation + ": " + describeTask(task));
        }
    }

    private void waitForClientTaskSucceeded(TaskInfo taskInfo, String operation)
        throws MeilisearchException {
        if (taskInfo == null) {
            throw new MeilisearchException("Meilisearch task was not created while trying to "
                + operation);
        }
        var taskUid = taskInfo.getTaskUid();
        var task = waitForClientTaskFinished(taskUid);
        if (task == null || task.getStatus() != TaskStatus.SUCCEEDED) {
            throw new MeilisearchException("Meilisearch task failed while trying to "
                + operation + ": " + describeTask(task));
        }
    }

    private Task waitForClientTaskFinished(int taskUid) throws MeilisearchException {
        var startedAt = System.currentTimeMillis();
        while (true) {
            var task = this.meilisearchClient.getTask(taskUid);
            if (isTaskFinished(task)) {
                return task;
            }
            if (System.currentTimeMillis() - startedAt >= TASK_WAIT_TIMEOUT_MS) {
                throw new MeilisearchException("Timed out while waiting for Meilisearch task "
                    + taskUid);
            }
            try {
                Thread.sleep(TASK_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MeilisearchException("Interrupted while waiting for Meilisearch task "
                    + taskUid);
            }
        }
    }

    private boolean isTaskFinished(Task task) {
        if (task == null) {
            return true;
        }
        var status = task.getStatus();
        return status != TaskStatus.ENQUEUED && status != TaskStatus.PROCESSING;
    }

    private String describeTask(Task task) {
        if (task == null) {
            return "task not found";
        }
        var message = "status=" + task.getStatus();
        var error = task.getError();
        if (error != null && error.getMessage() != null) {
            message += ", error=" + error.getMessage();
        }
        return message;
    }

    private void validateIndexSettings() throws MeilisearchException {
        var searchableAttributes = this.index.getSearchableAttributesSettings();
        if (!containsAll(searchableAttributes, SEARCH_ATTRIBUTES)) {
            throw new MeilisearchException(
                "Meilisearch searchable attributes were not applied, actual: "
                    + Arrays.toString(searchableAttributes));
        }

        var filterableAttributes = this.index.getFilterableAttributesSettings();
        if (!containsAll(filterableAttributes, FILTERABLE_ATTRIBUTES)) {
            throw new MeilisearchException(
                "Meilisearch filterable attributes were not applied, actual: "
                    + Arrays.toString(filterableAttributes));
        }

        var displayedAttributes = this.index.getDisplayedAttributesSettings();
        if (!containsAll(displayedAttributes, DISPLAYED_ATTRIBUTES)) {
            throw new MeilisearchException(
                "Meilisearch displayed attributes were not applied, actual: "
                    + Arrays.toString(displayedAttributes));
        }
    }

    private boolean containsAll(String[] actual, String[] expected) {
        if (actual == null) {
            return false;
        }
        var actualSet = Arrays.stream(actual)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (actualSet.contains("*")) {
            return true;
        }
        return Arrays.stream(expected).allMatch(actualSet::contains);
    }

    private void ensureIndexPrimaryKey(String indexName) throws MeilisearchException {
        var existingIndex = getExistingIndex(indexName);
        if (existingIndex == null) {
            createIndexWithPrimaryKey(indexName);
            return;
        }

        var primaryKey = existingIndex.getPrimaryKey();
        if (primaryKey == null || DOCUMENT_PRIMARY_KEY.equals(primaryKey)) {
            return;
        }

        log.warn("Recreating Meilisearch index {} because primary key is {}, expected {}",
            indexName, primaryKey, DOCUMENT_PRIMARY_KEY);
        var deleteTask = this.meilisearchClient.deleteIndex(indexName);
        waitForClientTaskSucceeded(deleteTask, "delete legacy index");
        createIndexWithPrimaryKey(indexName);
    }

    private Index getExistingIndex(String indexName) throws MeilisearchException {
        try {
            return this.meilisearchClient.getIndex(indexName);
        } catch (MeilisearchException e) {
            log.debug("Meilisearch index {} does not exist or cannot be fetched", indexName, e);
            return null;
        }
    }

    private void createIndexWithPrimaryKey(String indexName) throws MeilisearchException {
        var createTask = this.meilisearchClient.createIndex(indexName, DOCUMENT_PRIMARY_KEY);
        waitForClientTaskSucceeded(createTask, "create index with primary key");
        this.index = this.meilisearchClient.index(indexName);
        this.rebuildOnNextSuccessfulInitialization = true;
    }

    @Override
    public boolean available() {
        return available;
    }

    IndexStats getStats() throws MeilisearchException {
        var currentIndex = this.index;
        if (!available || currentIndex == null) {
            throw new IllegalStateException("Meilisearch is not available");
        }
        return currentIndex.getStats();
    }

    private HaloDocument cleanDocument(HaloDocument document) {
        if (document == null) {
            return document;
        }

        try {
            var originalJson = JsonUtils.mapper().writeValueAsString(document);
            var cleanedDocument = JsonUtils.mapper().readValue(originalJson, HaloDocument.class);
            
            cleanedDocument.setDescription(HtmlUtils.stripHtmlAndTrim(document.getDescription()));
            cleanedDocument.setContent(HtmlUtils.stripHtmlAndTrim(document.getContent()));
            
            return cleanedDocument;
        } catch (Exception e) {
            log.warn("Failed to clean document, using original", e);
            return document;
        }
    }

    @Override
    public void addOrUpdate(Iterable<HaloDocument> docs) {
        if (!available) {
            log.warn("Meilisearch is not available, skipping addOrUpdate");
            return;
        }
        if (docs == null) {
            log.warn("No documents provided, skipping addOrUpdate");
            return;
        }

        List<ObjectNode> documents = Streams.of(docs)
            .filter(Objects::nonNull)
            .map(this::cleanDocument)
            .map(this::toMeilisearchDocument)
            .toList();
        if (documents.isEmpty()) {
            return;
        }

        try {
            String documentsJson = JsonUtils.mapper().writeValueAsString(documents);
            waitForTaskSucceeded(
                this.index.addDocuments(documentsJson, DOCUMENT_PRIMARY_KEY),
                "add or update documents"
            );
        } catch (MeilisearchException | JsonProcessingException e) {
            log.error("Failed to add/update documents", e);
        }
    }

    private ObjectNode toMeilisearchDocument(HaloDocument document) {
        ObjectNode documentNode = JsonUtils.mapper().valueToTree(document);
        documentNode.put(DOCUMENT_PRIMARY_KEY, toMeilisearchDocumentId(document.getId()));
        return documentNode;
    }

    private String toMeilisearchDocumentId(String haloDocumentId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(haloDocumentId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is not available", e);
        }
    }

    @Override
    public void deleteDocument(Iterable<String> docIds) {
        if (!available) {
            log.warn("Meilisearch is not available, skipping deleteDocument");
            return;
        }
        if (docIds == null) {
            deleteAll();
            return;
        }

        var documentIds = Streams.of(docIds)
            .filter(id -> id != null && !id.isBlank())
            .map(this::toMeilisearchDocumentId)
            .toList();
        if (documentIds.isEmpty()) {
            return;
        }

        try {
            waitForTaskSucceeded(this.index.deleteDocuments(documentIds), "delete documents");
        } catch (MeilisearchException e) {
            log.error("Failed to delete documents", e);
        }
    }

    @Override
    public void deleteAll() {
        if (!available) {
            log.warn("Meilisearch is not available, skipping deleteAll");
            return;
        }

        try {
            waitForTaskSucceeded(this.index.deleteAllDocuments(), "delete all documents");
        } catch (MeilisearchException e) {
            log.error("Failed to delete all documents", e);
        }
    }

    @Override
    public run.halo.app.search.SearchResult search(SearchOption searchOption) {
        if (!available) {
            return emptyResult(searchOption);
        }

        var filter = new StringJoiner(" AND ");

        var filterRecycled = searchOption.getFilterRecycled();
        if (filterRecycled != null) {
            filter.add("recycled = " + filterRecycled);
        }

        var filterExposed = searchOption.getFilterExposed();
        if (filterExposed != null) {
            filter.add("exposed = " + filterExposed);
        }

        var filterPublished = searchOption.getFilterPublished();
        if (filterPublished != null) {
            filter.add("published = " + filterPublished);
        }

        var includeTypes = normalizeFilterValues(searchOption.getIncludeTypes());
        if (!includeTypes.isEmpty()) {
            var typeFilter = includeTypes.stream()
                .map(type -> "type = '" + type + "'")
                .collect(Collectors.joining(" OR "));
            filter.add("(" + typeFilter + ")");
        }

        var includeOwnerNames = normalizeFilterValues(searchOption.getIncludeOwnerNames());
        if (!includeOwnerNames.isEmpty()) {
            var ownerFilter = includeOwnerNames.stream()
                .map(owner -> "ownerName = '" + owner + "'")
                .collect(Collectors.joining(" OR "));
            filter.add("(" + ownerFilter + ")");
        }

        var includeTagNames = normalizeFilterValues(searchOption.getIncludeTagNames());
        if (!includeTagNames.isEmpty()) {
            var tagFilter = includeTagNames.stream()
                .map(tag -> "tags = '" + tag + "'")
                .collect(Collectors.joining(" AND "));
            filter.add("(" + tagFilter + ")");
        }

        var includeCategoryNames = normalizeFilterValues(searchOption.getIncludeCategoryNames());
        if (!includeCategoryNames.isEmpty()) {
            var categoryFilter = includeCategoryNames.stream()
                .map(category -> "categories = '" + category + "'")
                .collect(Collectors.joining(" AND "));
            filter.add("(" + categoryFilter + ")");
        }

        var searchRequestBuilder = SearchRequest.builder()
            .q(searchOption.getKeyword())
            .limit(searchOption.getLimit())
            .attributesToHighlight(HIGHLIGHT_ATTRIBUTES)
            .highlightPreTag(searchOption.getHighlightPreTag())
            .highlightPostTag(searchOption.getHighlightPostTag())
            .attributesToCrop(CROP_ATTRIBUTES)
            .cropLength(200)
            .cropMarker("");

        if (filter.length() > 0) {
            searchRequestBuilder.filter(new String[]{filter.toString()});
        }

        var searchRequest = searchRequestBuilder.build();

        try {
            return doSearch(searchOption, searchRequest);
        } catch (MeilisearchException e) {
            if (isInvalidSearchAttributesError(e) && recoverIndexSettingsAfterSearchFailure()) {
                try {
                    return doSearch(searchOption, searchRequest);
                } catch (MeilisearchException retryException) {
                    log.error("Failed to search after Meilisearch settings recovery",
                        retryException);
                    return emptyResult(searchOption);
                }
            }
            log.error("Failed to search", e);
            return emptyResult(searchOption);
        } catch (Exception e) {
            log.error("Unexpected error during search", e);
            return emptyResult(searchOption);
        }
    }

    private run.halo.app.search.SearchResult doSearch(SearchOption searchOption,
        SearchRequest searchRequest) throws MeilisearchException {
        Searchable meilisearchResult = this.index.search(searchRequest);

        var result = new run.halo.app.search.SearchResult();
        result.setLimit(searchOption.getLimit());
        result.setTotal((long) ((SearchResult) meilisearchResult).getEstimatedTotalHits());
        result.setKeyword(searchOption.getKeyword());
        result.setProcessingTimeMillis(meilisearchResult.getProcessingTimeMs());
        result.setHits(convertHits(meilisearchResult.getHits()));

        return result;
    }

    private boolean isInvalidSearchAttributesError(MeilisearchException e) {
        if (e == null) {
            return false;
        }
        var errorText = String.valueOf(e.getMessage()) + String.valueOf(e.getError())
            + String.valueOf(e);
        return errorText.contains("invalid_search_attributes_to_search_on");
    }

    private synchronized boolean recoverIndexSettingsAfterSearchFailure() {
        if (!available || this.index == null) {
            return false;
        }
        try {
            log.warn("Recovering Meilisearch index settings after invalid searchable attributes");
            initializeIndexSettings();
            return true;
        } catch (Exception recoveryError) {
            log.error("Failed to recover Meilisearch index settings", recoveryError);
            this.available = false;
            if (hasUsableConfiguration(this.currentHost, this.currentIndexName)) {
                cancelScheduledRetry();
                scheduleRetry(this.currentHost, this.currentApiKey, this.currentIndexName);
            }
            return false;
        }
    }

    private List<String> normalizeFilterValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }

    private List<HaloDocument> convertHits(List<HashMap<String, Object>> hits) {
        if (hits == null) {
            return List.of();
        }
        return hits.stream()
            .map(this::convertHit)
            .filter(Objects::nonNull)
            .toList();
    }

    private HaloDocument convertHit(HashMap<String, Object> hit) {
        if (hit == null) {
            return null;
        }
        try {
            var formatted = hit.get("_formatted");
            if (formatted instanceof Map<?, ?> formattedMap) {
                return JsonUtils.mapper().convertValue(formattedMap, HaloDocument.class);
            }
            return JsonUtils.mapper().convertValue(hit, HaloDocument.class);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to convert Meilisearch hit, skipping malformed hit", e);
            return null;
        }
    }

    @Override
    public void destroy() throws Exception {
        synchronized (this) {
            this.disposed = true;
            this.pluginStarted = false;
            this.rebuildPending = false;
            cancelScheduledRetry();
            resetState();
        }
        this.retryExecutor.shutdownNow();
    }

    @EventListener
    public void onApplicationEvent(ConfigUpdatedEvent event) {
        var properties = event.getMeilisearchProperties();

        var host = properties.getHost();
        var masterKey = properties.getMasterKey();
        var indexName = properties.getIndexName();

        refresh(host, masterKey, indexName);
    }

    @EventListener
    public void onApplicationEvent(PluginStartedEvent event) {
        var shouldPublishRebuild = false;
        synchronized (this) {
            this.pluginStarted = true;
            if (this.available && this.rebuildPending) {
                this.rebuildPending = false;
                shouldPublishRebuild = true;
            }
        }
        if (shouldPublishRebuild) {
            publishRebuildRequest();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        var configMapOpt = client.fetch(ConfigMap.class, "meilisearch-engine-config");
        if (configMapOpt.isEmpty()) {
            log.warn("Meilisearch configuration not found");
            this.rebuildOnNextSuccessfulInitialization = true;
            return;
        }

        var configMap = configMapOpt.get();
        var data = configMap.getData();
        if (data == null || !data.containsKey("basic")) {
            log.warn("Meilisearch configuration data is missing");
            this.rebuildOnNextSuccessfulInitialization = true;
            return;
        }

        try {
            var properties = JsonUtils.mapper().readValue(data.get("basic"), MeilisearchProperties.class);
            var host = properties.getHost();
            var masterKey = properties.getMasterKey();
            var indexName = properties.getIndexName();

            refresh(host, masterKey, indexName);
        } catch (Exception e) {
            log.error("Failed to parse Meilisearch configuration", e);
            this.rebuildOnNextSuccessfulInitialization = true;
        }
    }

    private run.halo.app.search.SearchResult emptyResult(SearchOption searchOption) {
        var result = new run.halo.app.search.SearchResult();
        result.setHits(List.of());
        result.setTotal(0L);
        if (searchOption != null) {
            result.setKeyword(searchOption.getKeyword());
            result.setLimit(searchOption.getLimit());
        }
        return result;
    }

    interface ClientFactory {
        Client create(Config config);
    }
}
