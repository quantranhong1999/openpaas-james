package com.linagora.tmail.james.jmap;

import static org.apache.james.backends.es.v7.IndexCreationFactory.ANALYZER;
import static org.apache.james.backends.es.v7.IndexCreationFactory.PROPERTIES;
import static org.apache.james.backends.es.v7.IndexCreationFactory.TYPE;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;

import javax.inject.Inject;

import org.apache.james.backends.es.v7.ElasticSearchConfiguration;
import org.elasticsearch.common.xcontent.XContentBuilder;

public class ContactMappingFactory {

    private final ElasticSearchConfiguration elasticSearchConfiguration;
    private final ElasticSearchContactConfiguration contactConfiguration;

    @Inject
    public ContactMappingFactory(ElasticSearchConfiguration configuration, ElasticSearchContactConfiguration contactConfiguration) {
        this.elasticSearchConfiguration = configuration;
        this.contactConfiguration = contactConfiguration;
    }

    public XContentBuilder generalContactIndicesSetting() throws IOException {
        return jsonBuilder()
            .startObject()
                .startObject("settings")
                    .field("number_of_shards", elasticSearchConfiguration.getNbShards())
                    .field("number_of_replicas", elasticSearchConfiguration.getNbReplica())
                    .field("index.write.wait_for_active_shards", elasticSearchConfiguration.getWaitForActiveShards())
                    .startObject("index")
                        .field("max_ngram_diff", contactConfiguration.getMaxNgramDiff())
                    .endObject()
                    .startObject("analysis")
                        .startObject(ANALYZER)
                            .startObject("ngram_filter_analyzer")
                                .field("tokenizer", "standard")
                                .startArray("filter")
                                    .value("ngram_filter")
                                    .value("lowercase")
                                .endArray()
                            .endObject()
                            .startObject("edge_ngram_filter_analyzer")
                                .field("tokenizer", "standard")
                                .startArray("filter")
                                    .value("edge_ngram_filter")
                                    .value("lowercase")
                                .endArray()
                            .endObject()
                        .endObject()
                        .startObject("filter")
                            .startObject("ngram_filter")
                                .field(TYPE, "ngram")
                                .field("min_gram", 3)
                                .field("max_gram", 5)
                            .endObject()
                            .startObject("edge_ngram_filter")
                                .field(TYPE, "edge_ngram")
                                .field("min_gram", 3)
                                .field("max_gram", 5)
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject()
            .endObject();
        }

    public XContentBuilder userContactMappingContent() throws IOException {
        return jsonBuilder()
            .startObject()
                    .startObject(PROPERTIES)
                        .startObject("accountId")
                            .field(TYPE, "keyword")
                        .endObject()
                        .startObject("contactId")
                            .field(TYPE, "text")
                        .endObject()
                        .startObject("email")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                            .field("search_analyzer", "standard")
                        .endObject()
                        .startObject("firstname")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                            .field("search_analyzer", "standard")
                        .endObject()
                        .startObject("surname")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                            .field("search_analyzer", "standard")
                        .endObject()
                    .endObject()
            .endObject();
    }

    public XContentBuilder domainContactMappingContent() throws IOException {
        return jsonBuilder()
            .startObject()
                    .startObject(PROPERTIES)
                        .startObject("domain")
                            .field(TYPE, "keyword")
                        .endObject()
                        .startObject("contactId")
                            .field(TYPE, "text")
                        .endObject()
                        .startObject("email")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                             .field("search_analyzer", "standard")
                        .endObject()
                        .startObject("firstname")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                            .field("search_analyzer", "standard")
                        .endObject()
                        .startObject("surname")
                            .field(TYPE, "text")
                            .field(ANALYZER, "ngram_filter_analyzer")
                            .field("search_analyzer", "standard")
                        .endObject()
                    .endObject()
            .endObject();
    }
}
