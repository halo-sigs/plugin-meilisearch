package run.halo.meilisearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.Searchable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.stream.Streams;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchEngine;
import run.halo.app.search.SearchOption;

@Slf4j
@Component
public class MeilisearchSearchEngine implements SearchEngine, DisposableBean,
    InitializingBean, ApplicationListener<ConfigUpdatedEvent> {

    private static final String[] HIGHLIGHT_ATTRIBUTES =
        {"title", "description", "content", "categories", "tags"};
    private static final String[] SEARCH_ATTRIBUTES = {"title", "description", "content"};
    private static final String[] CROP_ATTRIBUTES = {"description", "content"};

    private final ExtensionClient client;

    private Client meilisearchClient;
    private Index index;
    private volatile boolean available = false;

    public MeilisearchSearchEngine(ExtensionClient client) {
        this.client = client;
    }

    private void refresh(String host, String apiKey, String indexName) {
        if (this.available) {
            try {
                this.destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy MeilisearchSearchEngine during refreshing config", e);
            }
        }

        try {
            this.meilisearchClient = new Client(new Config(host, apiKey));
            this.index = this.meilisearchClient.index(indexName);

            this.index.updateSearchableAttributesSettings(SEARCH_ATTRIBUTES);
            this.index.updateFilterableAttributesSettings(new String[]{
                "published", "recycled", "exposed", "type", "categories", "tags"
            });
            this.index.updateDisplayedAttributesSettings(new String[]{
                "id", "metadataName", "title", "annotations", "description", "categories", "tags",
                "published", "recycled", "exposed", "ownerName", "creationTimestamp",
                "updateTimestamp", "permalink", "type", "content"
            });

            this.available = true;
            log.info("Meilisearch client initialized successfully, index: {}", indexName);
        } catch (MeilisearchException e) {
            log.error("Failed to initialize Meilisearch client", e);
            this.available = false;
        } catch (Exception e) {
            log.error("Unexpected error during Meilisearch initialization", e);
            this.available = false;
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void addOrUpdate(Iterable<HaloDocument> docs) {
        if (!available) {
            log.warn("Meilisearch is not available, skipping addOrUpdate");
            return;
        }

        List<HaloDocument> documents = Streams.of(docs).toList();

        try {
            String documentsJson = JsonUtils.mapper().writeValueAsString(documents);
            this.index.addDocuments(documentsJson, "metadataName");
        } catch (MeilisearchException | JsonProcessingException e) {
            log.error("Failed to add/update documents", e);
        }
    }

    @Override
    public void deleteDocument(Iterable<String> docIds) {
        if (!available) {
            log.warn("Meilisearch is not available, skipping deleteDocument");
            return;
        }

        var metadataNames = Streams.of(docIds).map(id -> {
            String[] split = id.split("-", 2);
            return split.length > 1 ? split[1] : id;
        }).toList();

        try {
            this.index.deleteDocuments(metadataNames);
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
            this.index.deleteAllDocuments();
        } catch (MeilisearchException e) {
            log.error("Failed to delete all documents", e);
        }
    }

    @Override
    public run.halo.app.search.SearchResult search(SearchOption searchOption) {
        if (!available) {
            return new run.halo.app.search.SearchResult();
        }

        StringJoiner filter = new StringJoiner(" AND ");
        filter.add("recycled = false");
        filter.add("exposed = true");
        filter.add("published = true");

        SearchRequest searchRequest = SearchRequest.builder()
            .q(searchOption.getKeyword())
            .limit(searchOption.getLimit())
            .filter(new String[]{filter.toString()})
            .attributesToSearchOn(SEARCH_ATTRIBUTES)
            .attributesToHighlight(HIGHLIGHT_ATTRIBUTES)
            .highlightPreTag(searchOption.getHighlightPreTag())
            .highlightPostTag(searchOption.getHighlightPostTag())
            .attributesToCrop(CROP_ATTRIBUTES)
            .cropLength(200)
            .cropMarker("")
            .build();

        try {
            Searchable meilisearchResult = this.index.search(searchRequest);

            var result = new run.halo.app.search.SearchResult();
            result.setLimit(searchOption.getLimit());
            result.setTotal((long) meilisearchResult.getHits().size());
            result.setKeyword(searchOption.getKeyword());
            result.setProcessingTimeMillis(meilisearchResult.getProcessingTimeMs());
            result.setHits(convertHits(meilisearchResult.getHits()));

            return result;
        } catch (MeilisearchException e) {
            log.error("Failed to search", e);
            return new run.halo.app.search.SearchResult();
        }
    }

    private List<HaloDocument> convertHits(List<HashMap<String, Object>> hits) {
        return hits.stream()
            .map(hit -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> formatted = (Map<String, Object>) hit.get("_formatted");
                if (formatted != null) {
                    return JsonUtils.mapper().convertValue(formatted, HaloDocument.class);
                } else {
                    return JsonUtils.mapper().convertValue(hit, HaloDocument.class);
                }
            })
            .toList();
    }

    @Override
    public void destroy() throws Exception {
        this.available = false;
    }

    @Override
    public void onApplicationEvent(ConfigUpdatedEvent event) {
        var properties = event.getMeilisearchProperties();

        var host = properties.getHost();
        var masterKey = properties.getMasterKey();
        var indexName = properties.getIndexName();

        if (host == null || host.isEmpty()) {
            log.warn("Meilisearch host is not configured");
            return;
        }

        refresh(host, masterKey, indexName);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        var configMapOpt = client.fetch(ConfigMap.class, "meilisearch-engine-config");
        if (configMapOpt.isEmpty()) {
            log.warn("Meilisearch configuration not found");
            return;
        }

        var configMap = configMapOpt.get();
        var data = configMap.getData();
        if (data == null || !data.containsKey("basic")) {
            log.warn("Meilisearch configuration data is missing");
            return;
        }

        try {
            var properties = JsonUtils.mapper().readValue(data.get("basic"), MeilisearchProperties.class);
            var host = properties.getHost();
            var masterKey = properties.getMasterKey();
            var indexName = properties.getIndexName();

            if (host != null && !host.isEmpty()) {
                refresh(host, masterKey, indexName);
            }
        } catch (Exception e) {
            log.error("Failed to parse Meilisearch configuration", e);
        }
    }
}