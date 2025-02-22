/**
 * MIT License
 * 
 * Copyright (c) 2018 - 2020 FormKiQ
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.formkiq.stacks.lambda.s3.awstest;

import com.formkiq.aws.dynamodb.ID;
import com.formkiq.aws.dynamodb.PaginationResults;
import com.formkiq.aws.dynamodb.model.DocumentItem;
import com.formkiq.aws.dynamodb.model.DocumentTag;
import com.formkiq.aws.dynamodb.model.DynamicDocumentItem;
import com.formkiq.aws.dynamodb.model.SearchMetaCriteria;
import com.formkiq.aws.dynamodb.model.SearchQuery;
import com.formkiq.aws.dynamodb.model.SearchTagCriteria;
import com.formkiq.aws.s3.S3ObjectMetadata;
import com.formkiq.stacks.dynamodb.DocumentItemDynamoDb;
import com.formkiq.stacks.dynamodb.DocumentService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LambdaFunctionConfiguration;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.formkiq.aws.dynamodb.SiteIdKeyGenerator.DEFAULT_SITE_ID;
import static com.formkiq.stacks.dynamodb.DocumentService.MAX_RESULTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test CloudFormation.
 */
@Isolated
public class AwsResourceTest extends AbstractAwsTest {
  /** Sleep Timeout. */
  private static final long SLEEP = 500L;
  /** Test Timeout. */
  private static final long TEST_TIMEOUT = 30;

  /**
   * Assert {@link LambdaFunctionConfiguration}.
   *
   * @param c {@link LambdaFunctionConfiguration}
   * @param event {@link String}
   */
  private static void assertLambdaFunctionConfigurations(final LambdaFunctionConfiguration c,
      final String event) {
    assertTrue(c.lambdaFunctionArn().contains("DocumentsS3Update"));
    assertEquals(1, c.events().size());
    Event e = c.events().get(0);
    assertEquals(event, e.toString());
  }

  /**
   * Assert Received Message.
   *
   * @param queueUrl {@link String}
   * @param type {@link String}
   * @throws InterruptedException InterruptedException
   */
  private static void assertSnsMessage(final String queueUrl, final String type)
      throws InterruptedException {

    List<Map<String, String>> receiveMessages;
    Gson gson = new GsonBuilder().create();

    do {

      receiveMessages = getSqsService().receiveMessages(queueUrl).messages().stream().map(m -> {
        String body = m.body();
        Map<String, String> map = gson.fromJson(body, Map.class);
        String message = map.get("Message");
        return (Map<String, String>) gson.fromJson(message, Map.class);
      }).filter(m -> type.equals(m.get("type"))).toList();

      if (receiveMessages.size() != 1) {
        TimeUnit.SECONDS.sleep(1);
      }

    } while (receiveMessages.size() != 1);

    Map<String, String> message = receiveMessages.get(0);

    assertNotNull(message.get("documentId"));
    assertNotNull(message.get("type"));

    if (type.equals(message.get("type"))) {

      if (!"delete".equals(type)) {
        assertNotNull(message.get("userId"));
      }

    } else {
      assertSnsMessage(queueUrl, type);
    }
  }

  /** {@link Gson}. */
  private final Gson gson = new GsonBuilder().create();

  private void assertQueueConfigurations(final QueueConfiguration q) {
    assertTrue(q.queueArn().contains("DocumentsStagingQueue"));
    assertEquals(1, q.events().size());
    Event e = q.events().get(0);
    assertEquals("s3:ObjectCreated:*", e.toString());
  }

  /**
   * Create SQS Queue.
   * 
   * @param queueName {@link String}
   * @return {@link CreateQueueResponse}
   */
  private CreateQueueResponse createSqsQueue(final String queueName) {
    Map<QueueAttributeName, String> attributes = new HashMap<>();
    attributes.put(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20");

    CreateQueueRequest request =
        CreateQueueRequest.builder().queueName(queueName).attributes(attributes).build();
    return getSqsService().createQueue(request);
  }

  /**
   * Subscribe Sqs to Sns.
   * 
   * @param topicArn {@link String}
   * @param queueUrl {@link String}
   * @return {@link String}
   */
  private String subscribeToSns(final String topicArn, final String queueUrl) {

    String queueArn = getSqsService().getQueueArn(queueUrl);

    Map<QueueAttributeName, String> attributes = new HashMap<>();
    attributes.put(QueueAttributeName.POLICY, "{\"Version\":\"2012-10-17\",\"Id\":\"Queue_Policy\","
        + "\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"sqs:SendMessage\","
        + "\"Resource\":\"*\"}}");

    SetQueueAttributesRequest setAttributes =
        SetQueueAttributesRequest.builder().queueUrl(queueUrl).attributes(attributes).build();
    getSqsService().setQueueAttributes(setAttributes);

    return getSnsService().subscribe(topicArn, "sqs", queueArn).subscriptionArn();
  }

  /**
   * Test Adding a file and then deleting it.
   * 
   * @throws Exception Exception
   */
  @Test
  @Timeout(value = TEST_TIMEOUT * 2)
  public void testAddDeleteFile01() throws Exception {
    // given
    String key = ID.uuid();

    String contentType = "text/plain";
    String createQueue = "createtest-" + UUID.randomUUID();
    String documentEventQueueUrl = createSqsQueue(createQueue).queueUrl();
    String snsDocumentEventArn = subscribeToSns(getSnsDocumentEventArn(), documentEventQueueUrl);

    try {

      // when
      key = writeToStaging(key, contentType);

      // then
      verifyFileExistsInDocumentsS3(key, contentType);
      verifyFileNotExistInStagingS3(key);
      assertSnsMessage(documentEventQueueUrl, "create");

      // when
      key = writeToStaging(key, contentType);

      // then
      verifyFileExistsInDocumentsS3(key, contentType);
      verifyFileNotExistInStagingS3(key);
      assertSnsMessage(documentEventQueueUrl, "create");

      // when
      getS3Service().deleteObject(getDocumentsbucketname(), key, null);

      // then
      assertSnsMessage(documentEventQueueUrl, "delete");

    } finally {
      getSnsService().unsubscribe(snsDocumentEventArn);
      getSqsService().deleteQueue(documentEventQueueUrl);
    }
  }

  /**
   * Test using a Presigned URL to Adding a file and then deleting it.
   * 
   * @throws Exception Exception
   */
  @Test
  @Timeout(value = TEST_TIMEOUT)
  public void testAddDeleteFile02() throws Exception {
    // given
    final Long contentLength = 36L;
    String key = ID.uuid();
    String contentType = "text/plain";
    DocumentItem item = new DocumentItemDynamoDb(key, new Date(), "test");

    // when
    getDocumentService().saveDocument(null, item, null);
    key = writeToDocuments(key, contentType);

    // then
    verifyFileExistsInDocumentsS3(key, contentType);

    item = getDocumentService().findDocument(null, key);

    while (true) {
      if (contentType.equals(item.getContentType())) {
        assertEquals(contentType, item.getContentType());
        assertEquals(contentLength, item.getContentLength());

        break;
      }

      item = getDocumentService().findDocument(null, key);
    }

    getS3Service().deleteObject(getDocumentsbucketname(), key, null);
  }

  /**
   * Test Adding a file directly to Documents Bucket and then deleting it.
   * 
   * @throws Exception Exception
   */
  @Test
  @Timeout(value = TEST_TIMEOUT)
  public void testAddDeleteFile03() throws Exception {
    // given
    final int statusCode = 200;
    HttpClient http = HttpClient.newHttpClient();
    String key = ID.uuid();

    String createQueue = "createtest-" + UUID.randomUUID();
    String documentQueueUrl = createSqsQueue(createQueue).queueUrl();
    String subscriptionDocumentArn = subscribeToSns(getSnsDocumentEventArn(), documentQueueUrl);

    String contentType = "text/plain";
    String content = "test content";

    try {

      DynamicDocumentItem doc = new DynamicDocumentItem(
          Map.of("documentId", key, "insertedDate", new Date(), "userId", "joe"));
      getDocumentService().saveDocumentItemWithTag(null, doc);

      // when
      URL url = getS3PresignerService().presignPutUrl(getDocumentsbucketname(), key,
          Duration.ofHours(1), null, null, Optional.empty(), null);
      HttpResponse<String> put =
          http.send(
              HttpRequest.newBuilder(url.toURI()).header("Content-Type", contentType)
                  .method("PUT", BodyPublishers.ofString(content)).build(),
              BodyHandlers.ofString());

      // then
      assertEquals(statusCode, put.statusCode());
      verifyFileExistsInDocumentsS3(key, contentType);
      assertSnsMessage(documentQueueUrl, "create");

      // when
      getS3Service().deleteObject(getDocumentsbucketname(), key, null);

      // then
      assertSnsMessage(documentQueueUrl, "delete");

    } finally {
      getSnsService().unsubscribe(subscriptionDocumentArn);
      getSqsService().deleteQueue(documentQueueUrl);
    }
  }

  /**
   * Test Adding a .FKB64 file directly to Staging Bucket.
   * 
   * @throws Exception Exception
   */
  @Test
  @Timeout(value = TEST_TIMEOUT)
  public void testAddDeleteFile04() throws Exception {
    // given
    final String key = ID.uuid();

    String contentType = "text/plain";
    String mycategory = "mycategory";
    String myvalue = ID.uuid();

    Map<String, Object> data = new HashMap<>();
    data.put("userId", "joesmith");
    data.put("contentType", contentType);
    data.put("isBase64", Boolean.TRUE);
    data.put("content", "dGhpcyBpcyBhIHRlc3Q=");
    data.put("tags",
        Arrays.asList(Map.of("key", "category", "value", "document"),
            Map.of("key", mycategory, "value", myvalue),
            Map.of("key", "status", "values", Arrays.asList("active", "notactive"))));
    byte[] json = this.gson.toJson(data).getBytes(StandardCharsets.UTF_8);

    // when
    getS3Service().putObject(getStagingdocumentsbucketname(), key + ".fkb64", json, contentType);

    // then
    SearchQuery query = new SearchQuery().tag(new SearchTagCriteria().key(mycategory).eq(myvalue));

    PaginationResults<DynamicDocumentItem> results;

    do {
      results = getSearchService().search(null, query, null, null, MAX_RESULTS);
      TimeUnit.SECONDS.sleep(1);
    } while (results.getResults().isEmpty());

    String documentId = results.getResults().get(0).getDocumentId();

    while (!getS3Service().getObjectMetadata(getDocumentsbucketname(), documentId, null)
        .isObjectExists()) {
      TimeUnit.SECONDS.sleep(1);
    }

    assertEquals("this is a test",
        getS3Service().getContentAsString(getDocumentsbucketname(), documentId, null));
  }

  /**
   * Test Document Update Lambda Sns.
   */
  @Test
  public void testDocumentUpdateLambdaSns() {
    // given
    // when
    final GetBucketNotificationConfigurationResponse response0 =
        getS3Service().getNotifications(getDocumentsbucketname());
    final GetBucketNotificationConfigurationResponse response1 =
        getS3Service().getNotifications(getStagingdocumentsbucketname());

    // then
    List<LambdaFunctionConfiguration> list =
        new ArrayList<>(response0.lambdaFunctionConfigurations());
    list.sort(new LambdaFunctionConfigurationComparator());

    assertEquals(0, response0.queueConfigurations().size());
    assertEquals(2, list.size());

    assertLambdaFunctionConfigurations(list.get(0), "s3:ObjectCreated:*");
    assertLambdaFunctionConfigurations(list.get(1), "s3:ObjectRemoved:*");

    assertEquals(1, response1.queueConfigurations().size());
    assertEquals(0, response1.lambdaFunctionConfigurations().size());

    assertQueueConfigurations(response1.queueConfigurations().get(0));
  }

  /**
   * Test SSM Parameter Store.
   */
  @Test
  public void testSsmParameters() {
    String appenvironment = getAppenvironment();
    String edition = getEdition();

    assertTrue(getDocumentsbucketname()
        .startsWith("formkiq-" + edition + "-" + appenvironment + "-documents-"));
    assertTrue(getStagingdocumentsbucketname()
        .startsWith("formkiq-" + edition + "-" + appenvironment + "-staging-"));
    assertTrue(
        getSsmService().getParameterValue("/formkiq/" + appenvironment + "/sns/DocumentEventArn")
            .contains("SnsDocumentEvent"));
    assertTrue(getSsmService()
        .getParameterValue("/formkiq/" + appenvironment + "/lambda/StagingCreateObject")
        .contains("StagingS3Create"));
    assertTrue(getSsmService()
        .getParameterValue("/formkiq/" + appenvironment + "/lambda/DocumentsUpdateObject")
        .contains("DocumentsS3Update"));

    String documentsUpdateUrl =
        getSsmService().getParameterValue("/formkiq/" + appenvironment + "/sqs/DocumentsUpdateUrl");
    assertTrue(documentsUpdateUrl.contains("DocumentsUpdateQueue"));
    assertTrue(documentsUpdateUrl.contains("https://"));

    String documentsUpdateArn =
        getSsmService().getParameterValue("/formkiq/" + appenvironment + "/sqs/DocumentsUpdateArn");
    assertTrue(documentsUpdateArn.contains("DocumentsUpdateQueue"));
    assertTrue(documentsUpdateArn.contains("arn:aws:sqs"));
  }

  /**
   * Test S3 Buckets Notifications.
   */
  @Test
  public void testStageDocumentNotifications() {
    // given
    // when
    final GetBucketNotificationConfigurationResponse response0 =
        getS3Service().getNotifications(getDocumentsbucketname());
    final GetBucketNotificationConfigurationResponse response1 =
        getS3Service().getNotifications(getStagingdocumentsbucketname());

    // then
    List<LambdaFunctionConfiguration> list =
        new ArrayList<>(response0.lambdaFunctionConfigurations());
    list.sort(new LambdaFunctionConfigurationComparator());

    assertEquals(0, response0.queueConfigurations().size());
    assertEquals(2, list.size());

    assertLambdaFunctionConfigurations(list.get(0), "s3:ObjectCreated:*");
    assertLambdaFunctionConfigurations(list.get(1), "s3:ObjectRemoved:*");

    assertEquals(1, response1.queueConfigurations().size());
    assertEquals(0, response1.lambdaFunctionConfigurations().size());

    assertQueueConfigurations(response1.queueConfigurations().get(0));
  }

  /**
   * Test Updating a file directly to Staging bucket.
   * 
   * @throws Exception Exception
   */
  @Test
  @Timeout(value = TEST_TIMEOUT)
  public void testUpdateStagingFile01() throws Exception {
    // given
    final String siteId = DEFAULT_SITE_ID;
    final String txt1 = "this is a test";
    final String txt2 = "this is a another test";

    String documentQueueUrl = createSqsQueue("createtest-" + UUID.randomUUID()).queueUrl();
    String subDocumentArn = subscribeToSns(getSnsDocumentEventArn(), documentQueueUrl);

    String contentType = "text/plain";
    String path = "user/home/test_" + UUID.randomUUID() + ".txt";

    try {

      // when
      getS3Service().putObject(getStagingdocumentsbucketname(), siteId + "/" + path,
          txt1.getBytes(StandardCharsets.UTF_8), contentType);

      SearchQuery q = new SearchQuery().meta(new SearchMetaCriteria().path(path));
      // SearchQuery q = new SearchQuery().tag(new SearchTagCriteria().key("path").eq(path));

      // then
      PaginationResults<DynamicDocumentItem> result =
          new PaginationResults<>(Collections.emptyList(), null);

      while (result.getResults().size() != 1) {
        result = getSearchService().search(siteId, q, null, null, DocumentService.MAX_RESULTS);
        Thread.sleep(SLEEP);
      }

      assertEquals(1, result.getResults().size());
      assertSnsMessage(documentQueueUrl, "create");
      String documentId = result.getResults().get(0).getDocumentId();
      DocumentItem item = getDocumentService().findDocument(siteId, documentId);
      assertEquals(item.getInsertedDate(), item.getLastModifiedDate());

      // given
      Collection<DocumentTag> tags =
          List.of(new DocumentTag(documentId, "status", "active", new Date(), "testuser"));
      getDocumentService().addTags(siteId, documentId, tags, null);

      // when
      getS3Service().putObject(getStagingdocumentsbucketname(), siteId + "/" + path,
          txt2.getBytes(StandardCharsets.UTF_8), contentType);

      // then
      waitForText(documentId);

      assertEquals(txt2,
          getS3Service().getContentAsString(getDocumentsbucketname(), documentId, null));
      assertSnsMessage(documentQueueUrl, "create");
      PaginationResults<DocumentTag> list =
          getDocumentService().findDocumentTags(siteId, documentId, null, MAX_RESULTS);
      assertEquals("[status]",
          list.getResults().stream().map(DocumentTag::getKey).toList().toString());

      item = getDocumentService().findDocument(siteId, documentId);
      assertNotEquals(item.getInsertedDate(), item.getLastModifiedDate());

    } finally {
      getSnsService().unsubscribe(subDocumentArn);
      getSqsService().deleteQueue(documentQueueUrl);
    }
  }

  /**
   * Verify File does NOT exist in Staging S3 Bucket.
   * 
   * @param key {@link String}
   * @throws InterruptedException InterruptedException
   */
  private void verifyFileNotExistInStagingS3(final String key) throws InterruptedException {
    while (true) {
      S3ObjectMetadata meta =
          getS3Service().getObjectMetadata(getStagingdocumentsbucketname(), key, null);

      if (!meta.isObjectExists()) {
        assertFalse(meta.isObjectExists());
        break;
      }
      Thread.sleep(SLEEP);
    }
  }

  private void waitForText(final String documentId) {
    while (true) {
      String txt = getS3Service().getContentAsString(getDocumentsbucketname(), documentId, null);
      if ("this is a another test".equals(txt)) {
        break;
      }
    }
  }

  /**
   * Write File to Documents S3.
   * 
   * @param key {@link String}
   * @param contentType {@link String}
   * @return {@link String}
   */
  private String writeToDocuments(final String key, final String contentType) {
    String data = ID.uuid();

    getS3Service().putObject(getDocumentsbucketname(), key, data.getBytes(StandardCharsets.UTF_8),
        contentType);

    return key;
  }

  /**
   * Write File to Staging S3.
   * 
   * @param key {@link String}
   * @param contentType {@link String}
   * @return {@link String}
   */
  private String writeToStaging(final String key, final String contentType) {
    String data = ID.uuid();

    getS3Service().putObject(getStagingdocumentsbucketname(), key,
        data.getBytes(StandardCharsets.UTF_8), contentType);

    return key;
  }
}
