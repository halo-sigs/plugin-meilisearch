package run.halo.meilisearch;

import lombok.Data;

@Data
public class MeilisearchProperties {

    private String host;

    private String masterKey;

    private String indexName;
}