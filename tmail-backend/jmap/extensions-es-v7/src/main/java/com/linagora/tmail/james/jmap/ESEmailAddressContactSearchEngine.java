package com.linagora.tmail.james.jmap;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Function;

import javax.mail.internet.AddressException;

import org.apache.james.backends.es.v7.DocumentId;
import org.apache.james.backends.es.v7.ElasticSearchIndexer;
import org.apache.james.backends.es.v7.ReactorElasticSearchClient;
import org.apache.james.backends.es.v7.RoutingKey;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.jmap.api.model.AccountId;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.linagora.tmail.james.jmap.contact.ContactFields;
import com.linagora.tmail.james.jmap.contact.DomainContactDocument;
import com.linagora.tmail.james.jmap.contact.EmailAddressContact;
import com.linagora.tmail.james.jmap.contact.EmailAddressContactSearchEngine;
import com.linagora.tmail.james.jmap.contact.UserContactDocument;

import reactor.core.publisher.Mono;

public class ESEmailAddressContactSearchEngine implements EmailAddressContactSearchEngine {
    private static final RoutingKey ROUTING_KEY = RoutingKey.fromString("routing");
    private static final String DELIMITER = ":";

    private final ElasticSearchIndexer userContactIndexer;
    private final ElasticSearchIndexer domainContactIndexer;
    private final ReactorElasticSearchClient client;
    private final ElasticSearchContactConfiguration configuration;
    private final ObjectMapper mapper;

    public ESEmailAddressContactSearchEngine(ReactorElasticSearchClient client, ElasticSearchContactConfiguration contactConfiguration) {
        this.client = client;
        this.userContactIndexer = new ElasticSearchIndexer(client, contactConfiguration.getUserContactWriteAliasName());
        this.domainContactIndexer = new ElasticSearchIndexer(client, contactConfiguration.getDomainContactWriteAliasName());
        this.configuration = contactConfiguration;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new GuavaModule());
        this.mapper.registerModule(new Jdk8Module());
    }

    @Override
    public Publisher<EmailAddressContact> index(AccountId accountId, ContactFields fields) {
        EmailAddressContact emailAddressContact = EmailAddressContact.of(fields);
        return Mono.fromCallable(() -> mapper.writeValueAsString(new UserContactDocument(accountId, emailAddressContact)))
            .flatMap(content -> userContactIndexer.index(computeUserContactDocumentId(accountId, fields.address()), content, ROUTING_KEY))
            .thenReturn(emailAddressContact);
    }

    @Override
    public Publisher<EmailAddressContact> index(Domain domain, ContactFields fields) {
        EmailAddressContact emailAddressContact = EmailAddressContact.of(fields);
        return Mono.fromCallable(() -> mapper.writeValueAsString(new DomainContactDocument(domain, emailAddressContact)))
            .flatMap(content -> domainContactIndexer.index(computeDomainContactDocumentId(domain, fields.address()), content, ROUTING_KEY))
            .thenReturn(emailAddressContact);
    }

    @Override
    public Publisher<EmailAddressContact> autoComplete(AccountId accountId, String part) {
        SearchRequest request = new SearchRequest(configuration.getUserContactReadAliasName().getValue(), configuration.getDomainContactReadAliasName().getValue())
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.boolQuery()
                    .must(QueryBuilders.multiMatchQuery(part, "email", "firstname", "surname"))
                    .should(QueryBuilders.matchQuery("accountId", accountId.getIdentifier()))
                    .should(QueryBuilders.matchQuery("domain", Username.of(accountId.getIdentifier()).getDomainPart()
                        .map(Domain::asString)
                        .orElse("")))
                    .minimumShouldMatch(1)));

        return client.search(request, RequestOptions.DEFAULT)
            .map(searchResponse -> searchResponse.getHits().getHits())
            .map(Arrays::asList)
            .flatMapIterable(Function.identity())
            .map(Throwing.function(this::extractContentFromHit).sneakyThrow());
    }

    private DocumentId computeUserContactDocumentId(AccountId accountId, MailAddress mailAddress) {
        return DocumentId.fromString(String.join(DELIMITER, accountId.getIdentifier(), mailAddress.asString()));
    }

    private DocumentId computeDomainContactDocumentId(Domain domain, MailAddress mailAddress) {
        return DocumentId.fromString(String.join(DELIMITER, domain.asString(), mailAddress.asString()));
    }

    private EmailAddressContact extractContentFromHit(SearchHit hit) throws AddressException {
        return new EmailAddressContact(UUID.fromString(hit.field("contactId").getValue()),
            new ContactFields(new MailAddress(String.valueOf(hit.field("email").getValue())),
                hit.field("firstname").getValue(),
                hit.field("surname").getValue()));
    }
}
