package com.linagora.tmail.james.jmap;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;

import org.elasticsearch.common.xcontent.XContentBuilder;

public class UserContactMappingFactory {

    public static XContentBuilder generateSetting(ElasticSearchContactConfiguration configuration) throws IOException {
        // TODO move the settings part to general index settings in James
        return jsonBuilder()
            .startObject()
                .startObject("settings")
                    .startObject("index")
                        .field("max_ngram_diff", configuration.getMaxNgramDiff())
                    .endObject()
                    .startObject("analysis")
                        .startObject("analyzer")
                            .startObject("ngram_filter_analyzer")
                                .field("tokenizer", "standard")
                                .startArray("filter")
                                    .value("ngram_filter")
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
                                .field("type", "ngram")
                                .field("min_gram", configuration.getMinNgram())
                                .field("max_ngram", configuration.getMinNgram() + configuration.getMaxNgramDiff())
                            .endObject()
                            .startObject("edge_ngram_filter")
                                .field("type", "edge_ngram")
                                .field("min_gram", configuration.getMinNgram())
                                .field("max_ngram", configuration.getMinNgram() + configuration.getMaxNgramDiff())
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject()
                .startObject("mappings")
                    .startObject("properties")
                        .startObject("accountId")
                            .field("type", "text")
                        .endObject()
                        .startObject("contactId")
                            .field("type", "text")
                        .endObject()
                        .startObject("email")
                            .field("type", "text")
                            .field("analyzer", "ngram_filter_analyzer")
                        .endObject()
                        .startObject("firstname")
                            .field("type", "text")
                            .field("analyzer", "edge_ngram_filter_analyzer")
                        .endObject()
                        .startObject("surname")
                            .field("type", "text")
                            .field("analyzer", "edge_ngram_filter_analyzer")
                        .endObject()
                    .endObject()
                .endObject()
            .endObject();
        }
}
