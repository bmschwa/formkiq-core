/**
 * MIT License
 * 
 * Copyright (c) 2018 - 2020 FormKiQ
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.formkiq.stacks.dynamodb;

import static com.formkiq.stacks.dynamodb.DbKeys.GSI1;
import static com.formkiq.stacks.dynamodb.DbKeys.GSI1_PK;
import static com.formkiq.stacks.dynamodb.DbKeys.GSI2;
import static com.formkiq.stacks.dynamodb.DbKeys.GSI2_PK;
import static com.formkiq.stacks.dynamodb.DbKeys.GSI2_SK;
import static com.formkiq.stacks.dynamodb.DbKeys.PK;
import static com.formkiq.stacks.dynamodb.DbKeys.PREFIX_DOCS;
import static com.formkiq.stacks.dynamodb.DbKeys.PREFIX_TAG;
import static com.formkiq.stacks.dynamodb.DbKeys.PREFIX_TAGS;
import static com.formkiq.stacks.dynamodb.DbKeys.SK;
import static com.formkiq.stacks.dynamodb.DbKeys.TAG_DELIMINATOR;
import static com.formkiq.stacks.dynamodb.SiteIdKeyGenerator.createDatabaseKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * 
 * Implementation {@link DocumentSearchService}.
 *
 */
public class DocumentSearchServiceImpl implements DocumentSearchService {

  /** {@link DocumentService}. */
  private DocumentService docService;

  /** Documents Table Name. */
  private String documentTableName;

  /** {@link DynamoDbClient}. */
  private final DynamoDbClient dynamoDB;

  /**
   * constructor.
   * 
   * @param documentService {@link DocumentService}
   * @param builder {@link DynamoDbConnectionBuilder}
   * @param documentsTable {@link String}
   */
  public DocumentSearchServiceImpl(final DocumentService documentService,
      final DynamoDbConnectionBuilder builder, final String documentsTable) {
    this.docService = documentService;

    if (documentsTable == null) {
      throw new IllegalArgumentException("Table name is null");
    }

    this.dynamoDB = builder.build();
    this.documentTableName = documentsTable;
  }

  /**
   * Filter Document Tags.
   * @param docMap {@link Map}
   * @param search {@link SearchTagCriteria}
   * @return {@link Map}
   */
  private Map<String, Map<String, AttributeValue>> filterDocumentTags(
      final Map<String, Map<String, AttributeValue>> docMap, final SearchTagCriteria search) {

    Map<String, Map<String, AttributeValue>> map = docMap;

    if (search.eq() != null || search.beginsWith() != null) {

      map = map.entrySet().stream().filter(x -> {
        
        AttributeValue value = x.getValue().get("tagValue");
        AttributeValue values = x.getValue().get("tagValues");
        
        boolean result = false;
        if (values != null) {

          Optional<AttributeValue> val = values.l().stream().filter(v -> {            
            return search.beginsWith() != null ? v.s().startsWith(search.beginsWith())
                : v.s().equals(search.eq());
          }).findFirst();
          
          result = val.isPresent();
          if (result) {
            x.getValue().remove("tagValues");
            x.getValue().put("tagValue", val.get());
          }
         
        } else if (value != null) {
          result = search.beginsWith() != null ? value.s().startsWith(search.beginsWith())
              : value.s().equals(search.eq());
        }

        return result;

      }).collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue()));
    }

    return map;
  }

  /**
   * Find Document Tag records.
   * @param siteId DynamoDB siteId.
   * @param documentIds {@link Collection} {@link String}
   * @param tagKey {@link String}
   * @return {@link Map}
   */
  private Map<String, Map<String, AttributeValue>> findDocumentsTags(final String siteId,
      final Collection<String> documentIds, final String tagKey) {
    
    Map<String, Map<String, AttributeValue>> map = new HashMap<>();

    List<Map<String, AttributeValue>> keys = documentIds.stream()
        .map(id -> Map.of(PK,
            AttributeValue.builder().s(createDatabaseKey(siteId, PREFIX_DOCS + id)).build(), SK,
            AttributeValue.builder().s(PREFIX_TAGS + tagKey).build()))
        .collect(Collectors.toList());
    
    Map<String, KeysAndAttributes> items =
        Map.of(this.documentTableName, KeysAndAttributes.builder().keys(keys).build());
    BatchGetItemRequest batchReq = BatchGetItemRequest.builder().requestItems(items).build();
    BatchGetItemResponse batchResponse = this.dynamoDB.batchGetItem(batchReq);

    Collection<List<Map<String, AttributeValue>>> values = batchResponse.responses().values();
    
    if (!values.isEmpty()) {
      List<Map<String, AttributeValue>> list = values.iterator().next();
      
      list.forEach(m -> {
        
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("type", m.get("type"));
        item.put("tagKey", m.get("tagKey"));

        if (m.containsKey("tagValue")) {
          item.put("tagValue", m.get("tagValue"));
        }

        if (m.containsKey("tagValues")) {
          item.put("tagValues", m.get("tagValues"));
        }
        
        String documentId = m.get("documentId").s();
        map.put(documentId, item);
      });
    }    

    return map;
  }

  /**
   * Find Document that match tagKey & tagValue.
   *
   * @param siteId DynamoDB siteId.
   * @param key {@link String}
   * @param value {@link String}
   * @param token {@link PaginationMapToken}
   * @param maxresults int
   * @return {@link PaginationResults}
   */
  private PaginationResults<DynamicDocumentItem> findDocumentsTagStartWith(final String siteId,
      final String key, final String value, final PaginationMapToken token, final int maxresults) {

    String expression = GSI2_PK + " = :pk and begins_with(" + GSI2_SK + ", :sk)";

    Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
    values.put(":pk",
        AttributeValue.builder().s(createDatabaseKey(siteId, PREFIX_TAG + key)).build());
    values.put(":sk", AttributeValue.builder().s(value).build());

    return searchForDocuments(siteId, GSI2, expression, values, token, maxresults);
  }

  /**
   * Find Document that match tagKey.
   *
   * @param siteId DynamoDB siteId Key
   * @param key {@link String}
   * @param value {@link String}
   * @param token {@link PaginationMapToken}
   * @param maxresults int
   * @return {@link PaginationResults}
   */
  private PaginationResults<DynamicDocumentItem> findDocumentsWithTag(final String siteId,
      final String key, final String value, final PaginationMapToken token, final int maxresults) {

    String expression = GSI2_PK + " = :pk";

    Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
    values.put(":pk",
        AttributeValue.builder().s(createDatabaseKey(siteId, PREFIX_TAG + key)).build());

    return searchForDocuments(siteId, GSI2, expression, values, token, maxresults);
  }

  /**
   * Find Document that match tagKey & tagValue.
   *
   * @param siteId DynamoDB PK siteId
   * @param key {@link String}
   * @param value {@link String}
   * @param token {@link PaginationMapToken}
   * @param maxresults int
   * @return {@link PaginationResults}
   */
  private PaginationResults<DynamicDocumentItem> findDocumentsWithTagAndValue(final String siteId,
      final String key, final String value, final PaginationMapToken token, final int maxresults) {

    String expression = GSI1_PK + " = :pk";

    Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
    values.put(":pk", AttributeValue.builder()
        .s(createDatabaseKey(siteId, PREFIX_TAG + key + TAG_DELIMINATOR + value)).build());

    return searchForDocuments(siteId, GSI1, expression, values, token, maxresults);
  }

  @Override
  public PaginationResults<DynamicDocumentItem> search(final String siteId,
      final SearchQuery query, final PaginationMapToken token, final int maxresults) {

    SearchTagCriteria search = query.tag();
    search.isValid();

    PaginationResults<DynamicDocumentItem> result = null;
    String key = search.key();

    Collection<String> documentIds = query.documentIds();
    
    if (documentIds != null && !documentIds.isEmpty()) {

      Map<String, Map<String, AttributeValue>> docs = findDocumentsTags(siteId, documentIds, key);
      Map<String, Map<String, AttributeValue>> filteredDocs = filterDocumentTags(docs, search);
      
      List<String> fetchDocumentIds = new ArrayList<>(filteredDocs.keySet());

      List<DocumentItem> list = this.docService.findDocuments(siteId, fetchDocumentIds);
     
      List<DynamicDocumentItem> results =
          list != null ? list.stream().map(l -> new DocumentItemToDynamicDocumentItem().apply(l))
              .collect(Collectors.toList()) : Collections.emptyList();

      results.forEach(r -> {
        Map<String, AttributeValue> tagMap = filteredDocs.get(r.getDocumentId());
        DocumentTag tag = new AttributeValueToDocumentTag(siteId).apply(tagMap);
        r.put("matchedTag", new DocumentTagToDynamicDocumentTag().apply(tag));
      });

      result = new PaginationResults<>(results, null);
      
    } else {
      
      if (search.eq() != null) {
        result = findDocumentsWithTagAndValue(siteId, key, search.eq(), token, maxresults);
      } else if (search.beginsWith() != null) {
        result = findDocumentsTagStartWith(siteId, key, search.beginsWith(), token, maxresults);
      } else {
        result = findDocumentsWithTag(siteId, key, null, token, maxresults);
      }
    }

    return result;
  }

  /**
   * Search for Documents.
   *
   * @param siteId DynamoDB PK siteId
   * @param index {@link String}
   * @param expression {@link String}
   * @param values {@link Map} {@link String} {@link AttributeValue}
   * @param token {@link PaginationMapToken}
   * @param maxresults int
   * @return {@link PaginationResults} {@link DocumentItemSearchResult}
   */
  private PaginationResults<DynamicDocumentItem> searchForDocuments(final String siteId,
      final String index, final String expression, final Map<String, AttributeValue> values,
      final PaginationMapToken token, final int maxresults) {

    Map<String, AttributeValue> startkey = new PaginationToAttributeValue().apply(token);

    QueryRequest q = QueryRequest.builder().tableName(this.documentTableName).indexName(index)
        .keyConditionExpression(expression).expressionAttributeValues(values)
        .exclusiveStartKey(startkey).scanIndexForward(Boolean.FALSE)
        .limit(Integer.valueOf(maxresults)).build();

    QueryResponse result = this.dynamoDB.query(q);

    Map<String, DocumentTag> tags = new HashMap<>();
    result.items().forEach(s -> {
      String documentId = s.get("documentId").s();

      String tagKey = s.containsKey("tagKey") ? s.get("tagKey").s() : null;
      String tagValue = s.containsKey("tagValue") ? s.get("tagValue").s() : "";

      DocumentTag tag = tags.containsKey(documentId) ? tags.get(documentId)
          : new DocumentTag().setKey(tagKey).setValue(tagValue)
              .setType(DocumentTagType.USERDEFINED);

      if (tags.containsKey(documentId)) {

        if (tag.getValues() == null) {
          tag.setValues(new ArrayList<>());
          tag.getValues().add(tag.getValue());
          tag.setValue(null);
        }

        tag.getValues().add(tagValue);

      } else {
        tags.put(documentId, tag);
      }
    });
    
    List<String> documentIds = new ArrayList<>(tags.keySet());

    List<DocumentItem> list = this.docService.findDocuments(siteId, documentIds);
   
    List<DynamicDocumentItem> results =
        list != null ? list.stream().map(l -> new DocumentItemToDynamicDocumentItem().apply(l))
            .collect(Collectors.toList()) : Collections.emptyList();

    results.forEach(r -> {
      DocumentTag tag = tags.get(r.getDocumentId());
      r.put("matchedTag", new DocumentTagToDynamicDocumentTag().apply(tag));
    });

    return new PaginationResults<>(results, new QueryResponseToPagination().apply(result));
  }
}
