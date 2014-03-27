package org.apache.solr.core;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.util.Version;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;

public class SolrCoreCheckLockOnStartupTest extends SolrTestCaseJ4 {

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();

    System.setProperty("solr.directoryFactory", "org.apache.solr.core.SimpleFSDirectoryFactory");

    IndexWriterConfig indexWriterConfig = new IndexWriterConfig(TEST_VERSION_CURRENT, null);
    Directory directory = newFSDirectory(new File(dataDir, "index"));
    //creates a new index on the known location
    new IndexWriter(
        directory,
        indexWriterConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE)
    ).close();
    directory.close();
  }

  @Test
  public void testSimpleLockErrorOnStartup() throws Exception {

    Directory directory = newFSDirectory(new File(dataDir, "index"), new SimpleFSLockFactory());
    //creates a new IndexWriter without releasing the lock yet
    IndexWriter indexWriter = new IndexWriter(directory, new IndexWriterConfig(TEST_VERSION_CURRENT, null));

    ignoreException("locked");
    try {
      System.setProperty("solr.tests.lockType","simple");
      //opening a new core on the same index
      initCore("solrconfig-basic.xml", "schema.xml");
      if (checkForCoreInitException(LockObtainFailedException.class))
        return;
      fail("Expected " + LockObtainFailedException.class.getSimpleName());
    } finally {
      System.clearProperty("solr.tests.lockType");
      unIgnoreException("locked");
      indexWriter.close();
      directory.close();
      deleteCore();
    }
  }

  @Test
  public void testNativeLockErrorOnStartup() throws Exception {

    File indexDir = new File(dataDir, "index");
    log.info("Acquiring lock on {}", indexDir.getAbsolutePath());
    Directory directory = newFSDirectory(indexDir, new NativeFSLockFactory());
    //creates a new IndexWriter without releasing the lock yet
    IndexWriter indexWriter = new IndexWriter(directory, new IndexWriterConfig(TEST_VERSION_CURRENT, null));

    ignoreException("locked");
    try {
      System.setProperty("solr.tests.lockType","native");
      //opening a new core on the same index
      initCore("solrconfig-basic.xml", "schema.xml");
      CoreContainer cc = h.getCoreContainer();
      if (checkForCoreInitException(LockObtainFailedException.class))
        return;
      fail("Expected " + LockObtainFailedException.class.getSimpleName());
    } finally {
      System.clearProperty("solr.tests.lockType");
      unIgnoreException("locked");
      indexWriter.close();
      directory.close();
      deleteCore();
    }
  }

  private boolean checkForCoreInitException(Class<? extends Exception> clazz) {
    for (Map.Entry<String, Exception> entry : h.getCoreContainer().getCoreInitFailures().entrySet()) {
      for (Throwable t = entry.getValue(); t != null; t = t.getCause()) {
        if (clazz.isInstance(t))
          return true;
      }
    }
    return false;
  }
}
