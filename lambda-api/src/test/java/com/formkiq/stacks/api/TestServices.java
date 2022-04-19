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
package com.formkiq.stacks.api;

import java.net.URISyntaxException;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import com.formkiq.aws.s3.S3ConnectionBuilder;
import com.formkiq.aws.sqs.SqsConnectionBuilder;
import com.formkiq.aws.sqs.SqsService;
import com.formkiq.aws.ssm.SsmConnectionBuilder;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * 
 * Singleton for Test Services.
 *
 */
public final class TestServices {
  
  /** LocalStack {@link DockerImageName}. */
  private static final DockerImageName LOCALSTACK_IMAGE =
      DockerImageName.parse("localstack/localstack:0.12.2");
  /** SQS Websockets Queue. */
  public static final String SQS_WEBSOCKET_QUEUE = "websockets";
  /** SQS Document Formats Queue. */
  public static final String SQS_DOCUMENT_FORMATS_QUEUE = "documentFormats";
  /** {@link String}. */
  public static final String BUCKET_NAME = "testbucket";
  /** {@link String}. */
  public static final String STAGE_BUCKET_NAME = "stagebucket";
  /** App Environment. */
  public static final String FORMKIQ_APP_ENVIRONMENT = "test";
  /** Aws Region. */
  public static final Region AWS_REGION = Region.US_EAST_1;
  /** {@link LocalStackContainer}. */
  private static LocalStackContainer localstack = null;
  /** {@link SqsService}. */
  private static SqsService sqsservice = null;
  /** SQS Sns Create QueueUrl. */
  private static String sqsDocumentFormatsQueueUrl;
  /** SQS Websocket Queue Url. */
  private static String sqsWebsocketQueueUrl;
  /** {@link SqsConnectionBuilder}. */
  private static SqsConnectionBuilder sqsConnection;
  /** {@link S3ConnectionBuilder}. */
  private static S3ConnectionBuilder s3Connection;
  /** {@link SsmConnectionBuilder}. */
  private static SsmConnectionBuilder ssmConnection;
  
  /**
   * Get Singleton Instance of {@link LocalStackContainer}.
   * @return {@link LocalStackContainer}
   */
  @SuppressWarnings("resource")
  public static LocalStackContainer getLocalStack() {
    if (localstack == null) {
      localstack = new LocalStackContainer(LOCALSTACK_IMAGE).withServices(Service.S3, Service.SQS,
          Service.SSM);
    }

    return localstack;
  }

  /**
   * Get Singleton {@link S3ConnectionBuilder}.
   * @return {@link S3ConnectionBuilder}
   * @throws URISyntaxException URISyntaxException
   */
  @SuppressWarnings("resource")
  public static S3ConnectionBuilder getS3Connection() throws URISyntaxException {
    if (s3Connection == null) {
      AwsCredentialsProvider cred = StaticCredentialsProvider
          .create(AwsSessionCredentials.create("ACCESSKEY", "SECRETKEY", "TOKENKEY"));
      
      s3Connection = new S3ConnectionBuilder().setCredentials(cred).setRegion(AWS_REGION)
          .setEndpointOverride(getLocalStack().getEndpointOverride(Service.S3).toString());
    }

    return s3Connection;
  }
  
  /**
   * Get Singleton {@link SqsConnectionBuilder}.
   * @return {@link SqsConnectionBuilder}
   * @throws URISyntaxException URISyntaxException
   */
  @SuppressWarnings("resource")
  public static SqsConnectionBuilder getSqsConnection() throws URISyntaxException {
    if (sqsConnection == null) {
      AwsCredentialsProvider cred = StaticCredentialsProvider
          .create(AwsSessionCredentials.create("ACCESSKEY", "SECRETKEY", "TOKENKEY"));

      sqsConnection = new SqsConnectionBuilder().setCredentials(cred).setRegion(AWS_REGION)
          .setEndpointOverride(getLocalStack().getEndpointOverride(Service.SQS).toString());
    }

    return sqsConnection;
  }
  
  /**
   * Get Sqs Documents Formats Queue Url.
   * @return {@link String}
   * @throws URISyntaxException URISyntaxException
   */
  public static String getSqsDocumentFormatsQueueUrl() throws URISyntaxException {
    if (sqsDocumentFormatsQueueUrl == null) {
      sqsDocumentFormatsQueueUrl =
          getSqsService().createQueue(SQS_DOCUMENT_FORMATS_QUEUE).queueUrl();
    }

    return sqsDocumentFormatsQueueUrl;
  }
  
  /**
   * Get Singleton Instance of {@link SqsService}.
   * @return {@link SqsService}
   * @throws URISyntaxException URISyntaxException
   */
  public static SqsService getSqsService() throws URISyntaxException {
    if (sqsservice == null) {
      sqsservice = new SqsService(getSqsConnection());
    }

    return sqsservice;
  }

  /**
   * Get Sqs Documents Formats Queue Url.
   * @return {@link String}
   * @throws URISyntaxException URISyntaxException
   */
  public static String getSqsWebsocketQueueUrl() throws URISyntaxException {
    if (sqsWebsocketQueueUrl == null) {
      sqsWebsocketQueueUrl =
          getSqsService().createQueue(SQS_WEBSOCKET_QUEUE).queueUrl();
    }

    return sqsWebsocketQueueUrl;
  }
  
  /**
   * Get Singleton {@link SsmConnectionBuilder}.
   * @return {@link SsmConnectionBuilder}
   * @throws URISyntaxException URISyntaxException
   */
  @SuppressWarnings("resource")
  public static SsmConnectionBuilder getSsmConnection() throws URISyntaxException {
    if (ssmConnection == null) {
      AwsCredentialsProvider cred = StaticCredentialsProvider
          .create(AwsSessionCredentials.create("ACCESSKEY", "SECRETKEY", "TOKENKEY"));

      ssmConnection = new SsmConnectionBuilder().setCredentials(cred).setRegion(AWS_REGION)
          .setEndpointOverride(getLocalStack().getEndpointOverride(Service.SSM).toString());
    }

    return ssmConnection;
  }

  private TestServices() {
  }
}
