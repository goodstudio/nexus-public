/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.testsuite.testsupport;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.DefaultBlobStoreProvider;
import org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationBuilder;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.net.PortAllocator;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.thread.TcclBlock;
import org.sonatype.nexus.jmx.reflect.ManagedObject;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.KERNEL;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * When enabled this replaces the default {@link BlobStore} with an S3 store for tests.
 */
@FeatureFlag(name = "nexus.test.default.s3")
@ManagedObject
@ManagedLifecycle(phase = KERNEL)
@Named
@Singleton
@Priority(Integer.MAX_VALUE)
public class TestDefaultS3BlobStoreProvider
    extends StateGuardLifecycleSupport
    implements DefaultBlobStoreProvider
{
  private GenericContainer<?> s3MockContainer = null;

  private final boolean useReal;

  private final String s3Bucket;

  private final String region;

  private final String accessKey;

  private final String accessSecret;

  private String endpoint;

  private final String prefix;

  private final boolean forcePathStyle;

  private final ClassLoader classLoader;

  @Inject
  public TestDefaultS3BlobStoreProvider(
      @Named("nexus-uber") final ClassLoader classLoader,
      @Nullable @Named("nexus.test.s3.bucket") final String bucket,
      @Nullable @Named("nexus.test.s3.region") final String region,
      @Nullable @Named("nexus.test.s3.accessKey") final String accessKey,
      @Nullable @Named("nexus.test.s3.accessSecret") final String accessSecret,
      @Nullable @Named("nexus.test.s3.endpoint") final String endpoint,
      @Nullable @Named("${nexus.test.s3.forcePathStyle:-true}") final Boolean forcePathStyle)
  {

    this.classLoader = classLoader;
    this.s3Bucket = Optional.ofNullable(bucket).orElse(UUID.randomUUID().toString());
    this.region = Optional.ofNullable(region).orElse("us-east-1");
    this.accessKey = Optional.ofNullable(accessKey).orElse("admin");
    this.accessSecret = Optional.ofNullable(accessSecret).orElse("admin");
    this.forcePathStyle = Optional.ofNullable(forcePathStyle).orElse(true);
    this.endpoint = endpoint;
    this.useReal = Objects.nonNull(endpoint);
    this.prefix = UUID.randomUUID().toString();
  }

  @Override
  @Guarded(by = STARTED)
  public BlobStoreConfiguration get(final Supplier<BlobStoreConfiguration> configurationSupplier) {
    return S3BlobStoreConfigurationBuilder.builder(configurationSupplier, BlobStoreManager.DEFAULT_BLOBSTORE_NAME)
        .bucket(s3Bucket)
        .region(region)
        .endpoint(endpoint)
        .prefix(prefix)
        .expiration(0)
        .accessKey(accessKey)
        .accessSecret(accessSecret)
        .forcePathStyle(forcePathStyle)
        .build();
  }

  @Override
  protected void doStart() throws Exception {
    if (useReal) {
      // we're using the real S3 server
      return;
    }
    // the existence property indicates maven is managing the s3mock server
    String mavenMockEndpoint = System.getProperty("mock.s3.service.endpoint");
    if (mavenMockEndpoint == null) {
      int s3MockPort = PortAllocator.nextFreePort();
      try (TcclBlock block = TcclBlock.begin(classLoader)) {
        s3MockContainer = new FixedHostPortGenericContainer("docker-all.repo.sonatype.com/adobe/s3mock")
            .withFixedExposedPort(s3MockPort, 9090)
            .withEnv("initialBuckets", s3Bucket)
            .waitingFor(Wait.forListeningPort());

        s3MockContainer.start();
        endpoint = "http://localhost:" + s3MockPort + "/";
      }
    }
    else {
      endpoint = mavenMockEndpoint;
    }
  }

  @Override
  protected void doStop() throws Exception {
    if (Objects.isNull(endpoint)) {
      if (s3MockContainer != null && s3MockContainer.isRunning()) {
        s3MockContainer.stop();
      }
    }
    else {
      AWSStaticCredentialsProvider credentialsProvider =
          new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, accessSecret));
      AmazonS3 s3 = AmazonS3ClientBuilder.standard()
          .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
          .withCredentials(credentialsProvider)
          .withRegion(Regions.valueOf(region))
          .withPathStyleAccessEnabled(forcePathStyle)
          .build();
      ObjectListing bucketContent = s3.listObjects(s3Bucket, prefix);

      do {
        bucketContent.getObjectSummaries().forEach(summary -> s3.deleteObject(s3Bucket, summary.getKey()));

        if (bucketContent.isTruncated()) {
          bucketContent = s3.listNextBatchOfObjects(bucketContent);
        }
      }
      while (!bucketContent.isTruncated());
    }
  }
}
