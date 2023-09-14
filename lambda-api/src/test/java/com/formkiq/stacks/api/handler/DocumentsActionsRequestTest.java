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
package com.formkiq.stacks.api.handler;

import static com.formkiq.stacks.dynamodb.ConfigService.CHATGPT_API_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.formkiq.aws.dynamodb.DynamicObject;
import com.formkiq.aws.dynamodb.model.DocumentItem;
import com.formkiq.aws.services.lambda.GsonUtil;
import com.formkiq.client.invoker.ApiException;
import com.formkiq.client.model.AddAction;
import com.formkiq.client.model.AddAction.TypeEnum;
import com.formkiq.client.model.AddActionParameters;
import com.formkiq.client.model.AddActionParameters.EngineEnum;
import com.formkiq.client.model.AddActionParameters.NotificationTypeEnum;
import com.formkiq.client.model.AddDocumentActionsRequest;
import com.formkiq.client.model.AddDocumentActionsResponse;
import com.formkiq.client.model.DocumentAction;
import com.formkiq.client.model.GetDocumentActionsResponse;
import com.formkiq.module.actions.Action;
import com.formkiq.module.actions.ActionStatus;
import com.formkiq.module.actions.ActionType;
import com.formkiq.module.actions.services.ActionsService;
import com.formkiq.stacks.dynamodb.ConfigService;
import com.formkiq.stacks.dynamodb.DocumentItemDynamoDb;
import com.formkiq.stacks.dynamodb.DocumentService;
import com.formkiq.testutils.aws.DynamoDbExtension;
import com.formkiq.testutils.aws.LocalStackExtension;
import software.amazon.awssdk.services.sqs.model.Message;

/** Unit Tests for request /documents/{documentId}/actions. */
@ExtendWith(DynamoDbExtension.class)
@ExtendWith(LocalStackExtension.class)
public class DocumentsActionsRequestTest extends AbstractApiClientRequestTest {

  /** {@link ConfigService}. */
  private ConfigService configService;
  /** {@link DocumentService}. */
  private DocumentService documentService;
  /** {@link ActionsService}. */
  private ActionsService service;

  /**
   * Before.
   * 
   * @throws Exception Exception
   */
  @BeforeEach
  public void before() throws Exception {
    this.service = getAwsServices().getExtension(ActionsService.class);
    this.documentService = getAwsServices().getExtension(DocumentService.class);
    this.configService = getAwsServices().getExtension(ConfigService.class);
  }

  /**
   * Save Document.
   * 
   * @param siteId {@link String}
   * @return {@link String}
   */
  private String saveDocument(final String siteId) {
    String documentId = UUID.randomUUID().toString();

    DocumentItem item = new DocumentItemDynamoDb(documentId, new Date(), "joe");
    this.documentService.saveDocument(siteId, item, null);
    return documentId;
  }

  /**
   * Get /documents/{documentId}/actions request.
   *
   * @throws Exception an error has occurred
   */
  @Test
  public void testHandleGetDocumentActions01() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken(siteId);
      String documentId = saveDocument(siteId);

      this.service.saveActions(siteId, documentId, Arrays.asList(new Action()
          .status(ActionStatus.COMPLETE).parameters(Map.of("test", "this")).type(ActionType.OCR)));

      // when
      GetDocumentActionsResponse response =
          this.documentActionsApi.getDocumentActions(documentId, siteId, null);

      // then
      List<DocumentAction> actions = response.getActions();
      assertEquals(1, actions.size());
      assertEquals("ocr", actions.get(0).getType());
      assertEquals("complete", actions.get(0).getStatus());
      assertEquals("{test=this}", actions.get(0).getParameters().toString());
    }
  }

  /**
   * POST /documents/{documentId}/actions request.
   *
   * @throws Exception an error has occurred
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDocumentActions01() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken(siteId);
      String documentId = saveDocument(siteId);

      this.service.saveActions(siteId, documentId,
          Arrays.asList(new Action().status(ActionStatus.COMPLETE)
              .parameters(Map.of("test", "this")).type(ActionType.FULLTEXT)));

      AddDocumentActionsRequest req = new AddDocumentActionsRequest().actions(Arrays.asList(
          new AddAction().type(TypeEnum.OCR)
              .parameters(new AddActionParameters().ocrParseTypes("text")),
          new AddAction().type(TypeEnum.WEBHOOK)
              .parameters(new AddActionParameters().url("https://localhost"))));

      // when
      AddDocumentActionsResponse response =
          this.documentActionsApi.addDocumentActions(documentId, siteId, req);

      // then
      assertEquals("Actions saved", response.getMessage());

      int i = 0;
      List<Action> actions = this.service.getActions(siteId, documentId);

      assertEquals(ActionType.FULLTEXT, actions.get(i).type());
      assertEquals(ActionStatus.COMPLETE, actions.get(i++).status());

      assertEquals(ActionType.OCR, actions.get(i).type());
      assertEquals(ActionStatus.PENDING, actions.get(i).status());
      assertEquals("{ocrParseTypes=text}", actions.get(i++).parameters().toString());

      assertEquals(ActionType.WEBHOOK, actions.get(i).type());
      assertEquals(ActionStatus.PENDING, actions.get(i).status());
      assertEquals("{url=https://localhost}", actions.get(i++).parameters().toString());

      List<Message> sqsMessages = getSqsMessages();
      assertEquals(1, sqsMessages.size());

      Map<String, String> map =
          GsonUtil.getInstance().fromJson(sqsMessages.get(0).body(), Map.class);

      map = GsonUtil.getInstance().fromJson(map.get("Message"), Map.class);
      assertNotNull(map.get("siteId"));
      assertEquals(documentId, map.get("documentId"));
      assertEquals("actions", map.get("type"));
    }
  }

  /**
   * POST /documents/{documentId}/actions missing 'type'.
   *
   * @throws Exception an error has occurred
   */
  @Test
  public void testHandlePostDocumentActions02() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken(siteId);
      String documentId = saveDocument(siteId);

      AddDocumentActionsRequest req = new AddDocumentActionsRequest().actions(Arrays
          .asList(new AddAction().parameters(new AddActionParameters().ocrParseTypes("text"))));

      // when
      try {
        this.documentActionsApi.addDocumentActions(documentId, siteId, req);
        fail();
      } catch (ApiException e) {
        final int status = 400;
        assertEquals(status, e.getCode());
        assertEquals("{\"message\":\"missing/invalid 'type' in body\"}", e.getResponseBody());
      }

      List<Action> actions = this.service.getActions(siteId, documentId);
      assertEquals(0, actions.size());
    }
  }

  /**
   * POST /documents/{documentId}/actions missing 'parameters' for documenttagging.
   *
   * @throws Exception an error has occurred
   */
  @Test
  public void testHandlePostDocumentActions03() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken(siteId);
      String documentId = saveDocument(siteId);

      AddDocumentActionsRequest req = new AddDocumentActionsRequest()
          .actions(Arrays.asList(new AddAction().type(TypeEnum.DOCUMENTTAGGING)));

      // when
      try {
        this.documentActionsApi.addDocumentActions(documentId, siteId, req);
        fail();
      } catch (ApiException e) {

        // then
        final int status = 400;
        assertEquals(status, e.getCode());
        assertEquals("{\"message\":\"missing/invalid 'type' in body\"}", e.getResponseBody());
      }

      List<Action> actions = this.service.getActions(siteId, documentId);
      assertEquals(0, actions.size());

      // given - engine
      this.configService.save(siteId, new DynamicObject(Map.of(CHATGPT_API_KEY, "asd")));

      req = new AddDocumentActionsRequest()
          .actions(Arrays.asList(new AddAction().type(TypeEnum.DOCUMENTTAGGING)
              .parameters(new AddActionParameters().engine(EngineEnum.CHATGPT).tags("something"))));

      // when - correct parameters
      AddDocumentActionsResponse response =
          this.documentActionsApi.addDocumentActions(documentId, siteId, req);

      // then
      assertEquals("Actions saved", response.getMessage());

      actions = this.service.getActions(siteId, documentId);
      assertEquals(1, actions.size());
      assertEquals(ActionType.DOCUMENTTAGGING, actions.get(0).type());
    }
  }

  /**
   * POST /documents/{documentId}/actions missing 'parameters' for notification.
   *
   * @throws Exception an error has occurred
   */
  @Test
  public void testHandlePostDocumentActions04() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken(siteId);
      String documentId = saveDocument(siteId);

      AddDocumentActionsRequest req = new AddDocumentActionsRequest().actions(Arrays
          .asList(new AddAction().type(TypeEnum.NOTIFICATION).parameters(new AddActionParameters()
              .notificationType(NotificationTypeEnum.EMAIL).notificationTo("test@formkiq.com"))));

      // when
      try {
        this.documentActionsApi.addDocumentActions(documentId, siteId, req);
        fail();
      } catch (ApiException e) {

        // then
        final int status = 400;
        assertEquals(status, e.getCode());
        assertEquals("{\"message\":\"missing/invalid 'type' in body\"}", e.getResponseBody());
      }

      List<Action> actions = this.service.getActions(siteId, documentId);
      assertEquals(0, actions.size());
    }
  }

  /**
   * POST /documents/{documentId}/actions for notification.
   *
   * @throws Exception an error has occurred
   */
  @Test
  public void testHandlePostDocumentActions05() throws Exception {

    for (String siteId : Arrays.asList(null, UUID.randomUUID().toString())) {
      // given
      setBearerToken("Admins");
      String documentId = saveDocument(siteId);

      this.configService.save(siteId,
          new DynamicObject(Map.of("NotificationEmail", "test@formkiq.com")));

      AddDocumentActionsRequest req = new AddDocumentActionsRequest().actions(Arrays
          .asList(new AddAction().type(TypeEnum.NOTIFICATION).parameters(new AddActionParameters()
              .notificationType(NotificationTypeEnum.EMAIL).notificationTo("test@formkiq.com"))));

      setBearerToken(siteId);

      // when
      AddDocumentActionsResponse response =
          this.documentActionsApi.addDocumentActions(documentId, siteId, req);

      // then
      assertEquals("Actions saved", response.getMessage());
      List<Action> actions = this.service.getActions(siteId, documentId);
      assertEquals(1, actions.size());
      assertEquals(ActionType.NOTIFICATION, actions.get(0).type());
    }
  }
}
