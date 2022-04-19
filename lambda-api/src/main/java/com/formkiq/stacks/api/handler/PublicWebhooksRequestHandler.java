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

import static com.formkiq.lambda.apigateway.ApiResponseStatus.MOVED_PERMANENTLY;
import static com.formkiq.lambda.apigateway.ApiResponseStatus.SC_OK;
import static com.formkiq.stacks.dynamodb.ConfigService.DOCUMENT_TIME_TO_LIVE;
import static com.formkiq.stacks.dynamodb.SiteIdKeyGenerator.createDatabaseKey;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.formkiq.aws.s3.S3Service;
import com.formkiq.lambda.apigateway.ApiAuthorizer;
import com.formkiq.lambda.apigateway.ApiGatewayRequestEvent;
import com.formkiq.lambda.apigateway.ApiGatewayRequestEventUtil;
import com.formkiq.lambda.apigateway.ApiGatewayRequestHandler;
import com.formkiq.lambda.apigateway.ApiMapResponse;
import com.formkiq.lambda.apigateway.ApiRedirectResponse;
import com.formkiq.lambda.apigateway.ApiRequestHandlerResponse;
import com.formkiq.lambda.apigateway.AwsServiceCache;
import com.formkiq.lambda.apigateway.exception.BadException;
import com.formkiq.lambda.apigateway.exception.TooManyRequestsException;
import com.formkiq.lambda.apigateway.exception.UnauthorizedException;
import com.formkiq.stacks.common.objects.DynamicObject;
import com.formkiq.stacks.dynamodb.SiteIdKeyGenerator;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.utils.StringUtils;

/** {@link ApiGatewayRequestHandler} for "/public/webhooks". */
public class PublicWebhooksRequestHandler
    implements ApiGatewayRequestHandler, ApiGatewayRequestEventUtil {

  /** To Milliseconds. */
  private static final long TO_MILLIS = 1000L;
  /** Extension for FormKiQ config file. */
  private static final String FORMKIQ_DOC_EXT = ".fkb64";

  private static boolean isContentTypeJson(final String contentType) {
    return contentType != null && "application/json".equals(contentType);
  }
  
  private static boolean isJsonValid(final String json) {
    
    try {
      GSON.fromJson(json, Object.class);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private DynamicObject buildDynamicObject(final AwsServiceCache awsservice, final String siteId,
      final String webhookId, final DynamicObject hook, final String body,
      final String contentType) {
    
    DynamicObject item = new DynamicObject(new HashMap<>());
    
    final String documentId = UUID.randomUUID().toString();
    
    if (contentType != null) {
      item.put("contentType", contentType);
    }
    
    item.put("content", body);
    item.put("documentId", documentId);
    item.put("userId", "webhook/" + hook.getOrDefault("path", "webhook"));
    item.put("path", "webhooks/" + webhookId);
    
    if (hook.containsKey("TimeToLive")) {
      item.put("TimeToLive", hook.get("TimeToLive"));
    } else {
      String ttl = awsservice.configService().get(siteId).getString(DOCUMENT_TIME_TO_LIVE);
      if (ttl != null) {
        item.put("TimeToLive", ttl);
      }
    }
    
    if (siteId != null) {
      item.put("siteId", siteId);
    }
    return item;
  }

  private ApiRequestHandlerResponse buildRedirect(final ApiGatewayRequestEvent event,
      final String redirectUri, final String body) {
    ApiRequestHandlerResponse response;
    StringBuilder sb = new StringBuilder();
    sb.append(redirectUri);
    
    Map<String, String> queryMap = decodeQueryString(body);
    
    String responseFields = getParameter(event, "responseFields");
    if (StringUtils.isNotBlank(responseFields)) {
      String[] fields = responseFields.split(",");
      for (int i = 0; i < fields.length; i++) {
        String value = queryMap.get(fields[i]);
        sb.append(i == 0 && redirectUri.indexOf("?") == -1 ? "?" : "&");
        sb.append(fields[i] + "=" + value);          
      }
    }
    
    response = new ApiRequestHandlerResponse(MOVED_PERMANENTLY,
        new ApiRedirectResponse(sb.toString()));
    return response;
  }
  
  private ApiRequestHandlerResponse buildResponse(final ApiGatewayRequestEvent event,
      final DynamicObject item) {
    
    String body = item.getString("content");
    String documentId = item.getString("documentId");
    String contentType = item.getString("contentType");
    
    ApiRequestHandlerResponse response = new ApiRequestHandlerResponse(SC_OK,
        new ApiMapResponse(Map.of("documentId", documentId)));
    
    String redirectUri = getParameter(event, "redirect_uri");
    
    if ("application/x-www-form-urlencoded".equals(contentType)
        && StringUtils.isNotBlank(redirectUri)) {
      
      response = buildRedirect(event, redirectUri, body);
      
    } else if (StringUtils.isNotBlank(redirectUri)) {
      response = new ApiRequestHandlerResponse(MOVED_PERMANENTLY,
          new ApiRedirectResponse(redirectUri));
    }
    
    return response;
  }
  
  private void checkIsWebhookValid(final DynamicObject hook)
      throws BadException, TooManyRequestsException, UnauthorizedException {
    if (hook == null || isExpired(hook)) {
      throw new BadException("invalid webhook url");
    }
    
    boolean isPrivate = isEnabled(hook, "private");
    boolean isEnabled = isEnabled(hook, "true");
    
    if (isPrivate && !isSupportPrivate()) {
      throw new UnauthorizedException("webhook is private");
    }
    
    if (!isPrivate && !isEnabled) {
      throw new TooManyRequestsException("webhook is disabled");
    }
  }
  
  protected boolean isSupportPrivate() {
    return false;
  }

  private Map<String, String> decodeQueryString(final String query) {
    Map<String, String> params = new LinkedHashMap<>();
    for (String param : query.split("&")) {
      String[] keyValue = param.split("=", 2);
      String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
      String value =
          keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
      if (!key.isEmpty()) {
        params.put(key, value);
      }
    }

    return params;
  }
  
  private String getIdempotencyKey(final ApiGatewayRequestEvent event) {
    return event.getHeaders().get("Idempotency-Key");
  }

  @Override
  public String getRequestUrl() {
    return "/public/webhooks";
  }

  /**
   * Is Webhook Enabled.
   * @param obj {@link DynamicObject}
   * @param val {@link String}
   * @return boolean
   */
  private boolean isEnabled(final DynamicObject obj, final String val) {
    
    boolean enabled = false;
    
    if (obj.containsKey("enabled") && obj.getString("enabled").equals(val)) {
      enabled = true;
    }

    return enabled;
  }
  
  /**
   * Is object expired.
   * @param obj {@link DynamicObject}
   * @return boolean
   */
  private boolean isExpired(final DynamicObject obj) {
    
    boolean expired = false;
    
    if (obj.containsKey("TimeToLive")) {
      long epoch = Long.parseLong(obj.getString("TimeToLive"));
      
      ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
      ZonedDateTime date = Instant.ofEpochMilli(epoch * TO_MILLIS).atZone(ZoneOffset.UTC);
      expired = now.isAfter(date);
    }

    return expired;
  }

  private boolean isIdempotencyCached(final AwsServiceCache awsservice,
      final ApiGatewayRequestEvent event, final String siteId, final DynamicObject item) {
    
    boolean cached = false;
    String idempotencyKey = getIdempotencyKey(event);
    
    if (idempotencyKey != null) {
      
      String key = SiteIdKeyGenerator.createDatabaseKey(siteId, "idkey#" + idempotencyKey);
      String documentId = awsservice.documentCacheService().read(key);
      
      if (documentId != null) {
        item.put("documentId", documentId);
        cached = true;
      } else {
        awsservice.documentCacheService().write(key, item.getString("documentId"), 2);
      }
    }
    
    return cached;
  }

  @Override
  public ApiRequestHandlerResponse post(final LambdaLogger logger,
      final ApiGatewayRequestEvent event, final ApiAuthorizer authorizer,
      final AwsServiceCache awsservice) throws Exception {

    String siteId = getParameter(event, "siteId");
        
    String webhookId = getPathParameter(event, "webhooks");
    DynamicObject hook = awsservice.webhookService().findWebhook(siteId, webhookId);
    
    checkIsWebhookValid(hook);
    
    String body = ApiGatewayRequestEventUtil.getBodyAsString(event);
    
    String contentType = getContentType(event);

    if (isContentTypeJson(contentType) && !isJsonValid(body)) {
      throw new BadException("body isn't valid JSON");
    }
    
    DynamicObject item = buildDynamicObject(awsservice, siteId, webhookId, hook, body, contentType);
    
    if (!isIdempotencyCached(awsservice, event, siteId, item)) {
      putObjectToStaging(logger, awsservice, item, siteId);
    }
    
    return buildResponse(event, item);
  }

  /**
   * Put Object to Staging Bucket.
   * @param logger {@link LambdaLogger}
   * @param awsservice {@link AwsServiceCache}
   * @param item {@link DynamicObject}
   * @param siteId {@link String}
   */
  private void putObjectToStaging(final LambdaLogger logger, final AwsServiceCache awsservice,
      final DynamicObject item, final String siteId) {
    
    String s = GSON.toJson(item);

    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);

    String key = createDatabaseKey(siteId, item.getString("documentId") + FORMKIQ_DOC_EXT);
    logger.log("s3 putObject " + key + " into bucket " + awsservice.stages3bucket());

    S3Service s3 = awsservice.s3Service();
    try (S3Client client = s3.buildClient()) {
      s3.putObject(client, awsservice.stages3bucket(), key, bytes, item.getString("contentType"));
    }
  }
}
