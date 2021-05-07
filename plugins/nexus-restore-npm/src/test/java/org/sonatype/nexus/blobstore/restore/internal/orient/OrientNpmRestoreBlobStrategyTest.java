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
package org.sonatype.nexus.blobstore.restore.internal.orient;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityMetadata;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.npm.orient.NpmFacet;
import org.sonatype.nexus.repository.npm.repair.orient.NpmRepairPackageRootComponent;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.AssetBlob;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.Query;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.hash.HashCode;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter.P_NAME;

@RunWith(PowerMockRunner.class)
@PrepareForTest(UnitOfWork.class)
public class OrientNpmRestoreBlobStrategyTest
{
  private static final String TEST_BLOB_STORE_NAME = "test";

  private static final String TEST_PACKAGE_NAME = "query-string";

  private static final String TEST_TARBALL_NAME = "query-string-1.0.0.tgz";

  OrientNpmRestoreBlobStrategy underTest;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  RepositoryManager repositoryManager;

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  BlobStore blobStore;

  @Mock
  Blob blob;

  @Mock
  BlobAttributes blobAttributes;

  @Mock
  BlobMetrics blobMetrics;

  @Mock
  Asset asset;

  @Mock
  Component component;

  @Mock
  EntityMetadata entityMetadata;

  @Mock
  EntityId entityId;

  @Mock
  BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  Repository repository;

  @Mock
  StorageFacet storageFacet;

  @Mock
  StorageTx storageTx;

  @Mock
  Bucket bucket;

  @Mock
  NpmFacet npmFacet;
  
  @Mock
  NpmRepairPackageRootComponent npmRepairPackageRootComponent;

  Properties packageProps = new Properties();

  Properties tarballProps = new Properties();

  Properties repoRootProps = new Properties();

  byte[] blobBytes = "blobbytes".getBytes();

  @Before
  public void setup() {
    underTest = new OrientNpmRestoreBlobStrategy(nodeAccess, repositoryManager, blobStoreManager, new DryRunPrefix("dryrun"),
        npmRepairPackageRootComponent);

    packageProps.setProperty("@BlobStore.blob-name", TEST_PACKAGE_NAME);
    packageProps.setProperty("@Bucket.repo-name", "test-repo");
    packageProps.setProperty("size", "1000");
    packageProps.setProperty("@BlobStore.content-type", "application/xml");
    packageProps.setProperty("sha1", "b64de86ceaa4f0e4d8ccc44a26c562c6fb7fb230");

    tarballProps.setProperty("@BlobStore.blob-name", TEST_PACKAGE_NAME + "/-/" + TEST_TARBALL_NAME);
    tarballProps.setProperty("@Bucket.repo-name", "test-repo");
    tarballProps.setProperty("size", "2000");
    tarballProps.setProperty("@BlobStore.content-type", "application/x-tgz");
    tarballProps.setProperty("sha1", "244cb02c77ec2e74f78a9bd318218abc9c500a61");

    repoRootProps.setProperty("@BlobStore.blob-name", "-/all");
    repoRootProps.setProperty("@Bucket.repo-name", "test-repo");
    repoRootProps.setProperty("size", "3000");
    repoRootProps.setProperty("@BlobStore.content-type", "application/json");
    repoRootProps.setProperty("sha1", "e4edaa6af69865e35ceb0882ce61e460c07f700c");

    Mockito.when(repositoryManager.get("test-repo")).thenReturn(repository);

    Mockito.when(repository.optionalFacet(StorageFacet.class)).thenReturn(Optional.of(storageFacet));
    Mockito.when(repository.optionalFacet(NpmFacet.class)).thenReturn(Optional.of(npmFacet));
    Mockito.when(repository.facet(NpmFacet.class)).thenReturn(npmFacet);

    Mockito.when(storageFacet.txSupplier()).thenReturn(() -> storageTx);
    Mockito.when(storageFacet.blobStore()).thenReturn(blobStore);

    Mockito.when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    Mockito.when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(blobAttributes);

    Mockito.when(blobAttributes.isDeleted()).thenReturn(false);

    Mockito.when(storageTx.findBucket(repository)).thenReturn(bucket);
    Mockito.when(storageTx.findComponents(any(Query.class), any(Iterable.class))).thenReturn(singletonList(component));
    Mockito.when(storageTx.findAssetWithProperty(eq(P_NAME), anyString(), any(Bucket.class))).thenReturn(asset);

    Mockito.when(asset.componentId()).thenReturn(entityId);

    Mockito.when(component.getEntityMetadata()).thenReturn(entityMetadata);

    Mockito.when(entityMetadata.getId()).thenReturn(entityId);

    Mockito.when(blob.getId()).thenReturn(new BlobId("test"));
    Mockito.when(blob.getInputStream()).thenReturn(new ByteArrayInputStream(blobBytes));
    Mockito.when(blob.getMetrics()).thenReturn(blobMetrics);

    mockStatic(UnitOfWork.class);
    Mockito.when(UnitOfWork.currentTx()).thenReturn(storageTx);

    when(blobStoreConfiguration.getName()).thenReturn(TEST_BLOB_STORE_NAME);

    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    Mockito.when(nodeAccess.getId()).thenReturn("node");
  }

  @Test
  public void testCorrectChecksums() throws Exception {
    Map<HashAlgorithm, HashCode> expectedHashes = Collections
        .singletonMap(HashAlgorithm.SHA1, HashAlgorithm.SHA1.function().hashBytes(blobBytes));
    ArgumentCaptor<AssetBlob> assetBlobCaptor = ArgumentCaptor.forClass(AssetBlob.class);

    underTest.restore(packageProps, blob, blobStore, false);

    verify(npmFacet).putPackageRoot(eq(TEST_PACKAGE_NAME), assetBlobCaptor.capture(), eq(null));
    assertEquals("asset hashes do not match blob", expectedHashes, assetBlobCaptor.getValue().getHashes());
  }

  @Test
  public void testPackageRestore() throws Exception {
    underTest.restore(packageProps, blob, blobStore, false);
    verify(npmFacet).findPackageRootAsset(TEST_PACKAGE_NAME);
    verify(npmFacet).putPackageRoot(eq(TEST_PACKAGE_NAME), any(AssetBlob.class), eq(null));
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void testTarballRestore() throws Exception {
    underTest.restore(tarballProps, blob, blobStore, false);
    verify(npmFacet).findTarballAsset(TEST_PACKAGE_NAME, TEST_TARBALL_NAME);
    verify(npmFacet).putTarball(eq(TEST_PACKAGE_NAME), eq(TEST_TARBALL_NAME), any(AssetBlob.class), eq(null));
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void testRootRestore() throws Exception {
    underTest.restore(repoRootProps, blob, blobStore, false);
    verify(npmFacet).findRepositoryRootAsset();
    verify(npmFacet).putRepositoryRoot(any(AssetBlob.class), eq(null));
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void testRestoreSkipNotFacet() {
    Mockito.when(repository.optionalFacet(StorageFacet.class)).thenReturn(Optional.empty());

    underTest.restore(packageProps, blob, blobStore, false);
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Ignore("NEXUS-27545")
  @Test
  public void testRestoreSkipExistingPackage() throws Exception {
    Mockito.when(npmFacet.findPackageRootAsset(TEST_PACKAGE_NAME)).thenReturn(Mockito.mock(Asset.class));

    underTest.restore(packageProps, blob, blobStore, false);
    verify(npmFacet).findPackageRootAsset(TEST_PACKAGE_NAME);
    verify(npmFacet, Mockito.never()).putPackageRoot(any(), any(), any());
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void testRestoreDryRun() throws Exception {
    underTest.restore(packageProps, blob, blobStore, true);
    verify(npmFacet).findPackageRootAsset(TEST_PACKAGE_NAME);
    verify(npmFacet, Mockito.never()).putPackageRoot(any(), any(), any());
    Mockito.verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void runNpmRepairComponentAfter() {
    underTest.after(true, repository);
    
    verify(npmRepairPackageRootComponent).repairRepository(repository);
  }

  @Test
  public void doNotRunNpmRepairComponentAfterWhenUpdatingAssetsDisabled() {
    underTest.after(false, repository);

    verify(npmRepairPackageRootComponent, Mockito.never()).repairRepository(repository);
  }

  @Test
  public void shouldSkipDeletedBlob() throws Exception {
    when(blobAttributes.isDeleted()).thenReturn(true);
    underTest.restore(tarballProps, blob, blobStore, false);
    verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void shouldSkipOlderBlob() throws Exception {
    when(npmFacet.findTarballAsset(TEST_PACKAGE_NAME, TEST_TARBALL_NAME)).thenReturn(asset);
    when(asset.blobCreated()).thenReturn(DateTime.now());
    when(blobMetrics.getCreationTime()).thenReturn(DateTime.now().minusDays(1));
    underTest.restore(tarballProps, blob, blobStore, false);
    verify(npmFacet).findTarballAsset(TEST_PACKAGE_NAME, TEST_TARBALL_NAME);
    verifyNoMoreInteractions(npmFacet);
  }

  @Test
  public void shouldRestoreMoreRecentBlob() throws Exception {
    when(npmFacet.findTarballAsset(TEST_PACKAGE_NAME, TEST_TARBALL_NAME)).thenReturn(asset);
    when(asset.blobCreated()).thenReturn(DateTime.now().minusDays(1));
    when(blobMetrics.getCreationTime()).thenReturn(DateTime.now());
    underTest.restore(tarballProps, blob, blobStore, false);
    verify(npmFacet).findTarballAsset(TEST_PACKAGE_NAME, TEST_TARBALL_NAME);
    verify(npmFacet).putTarball(eq(TEST_PACKAGE_NAME), eq(TEST_TARBALL_NAME), any(AssetBlob.class), eq(null));
    verifyNoMoreInteractions(npmFacet);
  }
}
