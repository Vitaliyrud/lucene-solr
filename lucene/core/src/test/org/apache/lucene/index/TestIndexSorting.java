package org.apache.lucene.index;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.BinaryPoint;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.EarlyTerminatingSortingCollector;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.TestUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;

// nocommit test tie break
// nocommit test multiple sorts
// nocommit test update dvs
// nocommit test missing value

// nocommit test EarlyTerminatingCollector

// nocommit must test all supported SortField.Type

public class TestIndexSorting extends LuceneTestCase {

  public void testBasicString() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.STRING));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("zzz")));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("aaa")));
    w.addDocument(doc);
    w.commit();

    doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("mmm")));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    SortedDocValues values = leaf.getSortedDocValues("foo");
    assertEquals("aaa", values.get(0).utf8ToString());
    assertEquals("mmm", values.get(1).utf8ToString());
    assertEquals("zzz", values.get(2).utf8ToString());
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingStringFirst() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.STRING);
    sortField.setMissingValue(SortField.STRING_FIRST);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("zzz")));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("mmm")));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    SortedDocValues values = leaf.getSortedDocValues("foo");
    assertEquals(-1, values.getOrd(0));
    assertEquals("mmm", values.get(1).utf8ToString());
    assertEquals("zzz", values.get(2).utf8ToString());
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingStringLast() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.STRING);
    sortField.setMissingValue(SortField.STRING_LAST);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("zzz")));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new SortedDocValuesField("foo", new BytesRef("mmm")));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    SortedDocValues values = leaf.getSortedDocValues("foo");
    assertEquals("mmm", values.get(0).utf8ToString());
    assertEquals("zzz", values.get(1).utf8ToString());
    assertEquals(-1, values.getOrd(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testBasicLong() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.LONG));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", -1));
    w.addDocument(doc);
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    assertEquals(-1, values.get(0));
    assertEquals(7, values.get(1));
    assertEquals(18, values.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingLongFirst() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.LONG);
    sortField.setMissingValue(Long.valueOf(Long.MIN_VALUE));
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(0, values.get(0));
    assertFalse(docsWithField.get(0));
    assertEquals(7, values.get(1));
    assertEquals(18, values.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingLongLast() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.LONG);
    sortField.setMissingValue(Long.valueOf(Long.MAX_VALUE));
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(7, values.get(0));
    assertEquals(18, values.get(1));
    assertEquals(0, values.get(2));
    assertFalse(docsWithField.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testBasicInt() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.INT));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", -1));
    w.addDocument(doc);
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    assertEquals(-1, values.get(0));
    assertEquals(7, values.get(1));
    assertEquals(18, values.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingIntFirst() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.INT);
    sortField.setMissingValue(Integer.valueOf(Integer.MIN_VALUE));
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(0, values.get(0));
    assertFalse(docsWithField.get(0));
    assertEquals(7, values.get(1));
    assertEquals(18, values.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingIntLast() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.INT);
    sortField.setMissingValue(Integer.valueOf(Integer.MAX_VALUE));
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new NumericDocValuesField("foo", 18));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new NumericDocValuesField("foo", 7));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(7, values.get(0));
    assertEquals(18, values.get(1));
    assertEquals(0, values.get(2));
    assertFalse(docsWithField.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testBasicDouble() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.DOUBLE));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 18.0));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    doc = new Document();
    doc.add(new DoubleDocValuesField("foo", -1.0));
    w.addDocument(doc);
    w.commit();

    doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 7.0));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    assertEquals(-1.0, Double.longBitsToDouble(values.get(0)), 0.0);
    assertEquals(7.0, Double.longBitsToDouble(values.get(1)), 0.0);
    assertEquals(18.0, Double.longBitsToDouble(values.get(2)), 0.0);
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingDoubleFirst() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.DOUBLE);
    sortField.setMissingValue(Double.NEGATIVE_INFINITY);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 18.0));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 7.0));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(0.0, Double.longBitsToDouble(values.get(0)), 0.0);
    assertFalse(docsWithField.get(0));
    assertEquals(7.0, Double.longBitsToDouble(values.get(1)), 0.0);
    assertEquals(18.0, Double.longBitsToDouble(values.get(2)), 0.0);
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingDoubleLast() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.DOUBLE);
    sortField.setMissingValue(Double.POSITIVE_INFINITY);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 18.0));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new DoubleDocValuesField("foo", 7.0));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(7.0, Double.longBitsToDouble(values.get(0)), 0.0);
    assertEquals(18.0, Double.longBitsToDouble(values.get(1)), 0.0);
    assertEquals(0.0, Double.longBitsToDouble(values.get(2)), 0.0);
    assertFalse(docsWithField.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testBasicFloat() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.FLOAT));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new FloatDocValuesField("foo", 18.0f));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    doc = new Document();
    doc.add(new FloatDocValuesField("foo", -1.0f));
    w.addDocument(doc);
    w.commit();

    doc = new Document();
    doc.add(new FloatDocValuesField("foo", 7.0f));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    assertEquals(-1.0f, Float.intBitsToFloat((int) values.get(0)), 0.0f);
    assertEquals(7.0f, Float.intBitsToFloat((int) values.get(1)), 0.0f);
    assertEquals(18.0f, Float.intBitsToFloat((int) values.get(2)), 0.0f);
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingFloatFirst() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.FLOAT);
    sortField.setMissingValue(Float.NEGATIVE_INFINITY);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new FloatDocValuesField("foo", 18.0f));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new FloatDocValuesField("foo", 7.0f));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(0.0f, Float.intBitsToFloat((int) values.get(0)), 0.0f);
    assertFalse(docsWithField.get(0));
    assertEquals(7.0f, Float.intBitsToFloat((int) values.get(1)), 0.0f);
    assertEquals(18.0f, Float.intBitsToFloat((int) values.get(2)), 0.0f);
    r.close();
    w.close();
    dir.close();
  }

  public void testMissingFloatLast() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    SortField sortField = new SortField("foo", SortField.Type.FLOAT);
    sortField.setMissingValue(Float.POSITIVE_INFINITY);
    Sort indexSort = new Sort(sortField);
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Document doc = new Document();
    doc.add(new FloatDocValuesField("foo", 18.0f));
    w.addDocument(doc);
    // so we get more than one segment, so that forceMerge actually does merge, since we only get a sorted segment by merging:
    w.commit();

    // missing
    w.addDocument(new Document());
    w.commit();

    doc = new Document();
    doc.add(new FloatDocValuesField("foo", 7.0f));
    w.addDocument(doc);
    w.forceMerge(1);

    DirectoryReader r = DirectoryReader.open(w);
    LeafReader leaf = getOnlyLeafReader(r);
    assertEquals(3, leaf.maxDoc());
    NumericDocValues values = leaf.getNumericDocValues("foo");
    Bits docsWithField = leaf.getDocsWithField("foo");
    assertEquals(7.0f, Float.intBitsToFloat((int) values.get(0)), 0.0f);
    assertEquals(18.0f, Float.intBitsToFloat((int) values.get(1)), 0.0f);
    assertEquals(0.0f, Float.intBitsToFloat((int) values.get(2)), 0.0f);
    assertFalse(docsWithField.get(2));
    r.close();
    w.close();
    dir.close();
  }

  public void testRandom1() throws IOException {
    boolean withDeletes = random().nextBoolean();
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.LONG));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    final int numDocs = atLeast(1000);
    final FixedBitSet deleted = new FixedBitSet(numDocs);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("foo", random().nextInt(20)));
      doc.add(new StringField("id", Integer.toString(i), Store.YES));
      doc.add(new NumericDocValuesField("id", i));
      w.addDocument(doc);
      if (random().nextInt(5) == 0) {
        w.getReader().close();
      } else if (random().nextInt(30) == 0) {
        w.forceMerge(2);
      } else if (random().nextInt(4) == 0) {
        final int id = TestUtil.nextInt(random(), 0, i);
        deleted.set(id);
        w.deleteDocuments(new Term("id", Integer.toString(id)));
      }
    }

    // Check that segments are sorted
    DirectoryReader reader = w.getReader();
    for (LeafReaderContext ctx : reader.leaves()) {
      final SegmentReader leaf = (SegmentReader) ctx.reader();
      SegmentInfo info = leaf.getSegmentInfo().info;
      switch (info.getDiagnostics().get(IndexWriter.SOURCE)) {
        case IndexWriter.SOURCE_FLUSH:
          assertNull(info.getIndexSort());
          break;
        case IndexWriter.SOURCE_MERGE:
          assertEquals(indexSort, info.getIndexSort());
          final NumericDocValues values = leaf.getNumericDocValues("foo");
          long previous = Long.MIN_VALUE;
          for (int i = 0; i < leaf.maxDoc(); ++i) {
            final long value = values.get(i);
            assertTrue(value >= previous);
            previous = value;
          }
          break;
        default:
          fail();
      }
    }

    // Now check that the index is consistent
    IndexSearcher searcher = newSearcher(reader);
    for (int i = 0; i < numDocs; ++i) {
      TermQuery termQuery = new TermQuery(new Term("id", Integer.toString(i)));
      final TopDocs topDocs = searcher.search(termQuery, 1);
      if (deleted.get(i)) {
        assertEquals(0, topDocs.totalHits);
      } else {
        assertEquals(1, topDocs.totalHits);
        assertEquals(i, MultiDocValues.getNumericValues(reader, "id").get(topDocs.scoreDocs[0].doc));
        Document document = reader.document(topDocs.scoreDocs[0].doc);
        assertEquals(Integer.toString(i), document.get("id"));
      }
    }

    reader.close();
    w.close();
    dir.close();
  }

  static class UpdateRunnable implements Runnable {

    private final int numDocs;
    private final Random random;
    private final AtomicInteger updateCount;
    private final IndexWriter w;
    private final Map<Integer, Long> values;
    private final CountDownLatch latch;

    UpdateRunnable(int numDocs, Random random, CountDownLatch latch, AtomicInteger updateCount, IndexWriter w, Map<Integer, Long> values) {
      this.numDocs = numDocs;
      this.random = random;
      this.latch = latch;
      this.updateCount = updateCount;
      this.w = w;
      this.values = values;
    }

    @Override
    public void run() {
      try {
        latch.await();
        while (updateCount.decrementAndGet() >= 0) {
          final int id = random.nextInt(numDocs);
          final long value = random.nextInt(20);
          Document doc = new Document();
          doc.add(new StringField("id", Integer.toString(id), Store.NO));
          doc.add(new NumericDocValuesField("foo", value));

          synchronized (values) {
            w.updateDocument(new Term("id", Integer.toString(id)), doc);
            values.put(id, value);
          }

          switch (random.nextInt(10)) {
            case 0:
            case 1:
              // reopen
              DirectoryReader.open(w).close();
              break;
            case 2:
              w.forceMerge(3);
              break;
          }
        }
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

  }

  // There is tricky logic to resolve deletes that happened while merging
  public void testConcurrentUpdates() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.LONG));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Map<Integer, Long> values = new HashMap<>();

    final int numDocs = atLeast(100);
    Thread[] threads = new Thread[2];
    final AtomicInteger updateCount = new AtomicInteger(atLeast(1000));
    final CountDownLatch latch = new CountDownLatch(1);
    for (int i = 0; i < threads.length; ++i) {
      Random r = new Random(random().nextLong());
      threads[i] = new Thread(new UpdateRunnable(numDocs, r, latch, updateCount, w, values));
    }
    for (Thread thread : threads) {
      thread.start();
    }
    latch.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    w.forceMerge(1);
    DirectoryReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);
    for (int i = 0; i < numDocs; ++i) {
      final TopDocs topDocs = searcher.search(new TermQuery(new Term("id", Integer.toString(i))), 1);
      if (values.containsKey(i) == false) {
        assertEquals(0, topDocs.totalHits);
      } else {
        assertEquals(1, topDocs.totalHits);
        assertEquals(values.get(i).longValue(), MultiDocValues.getNumericValues(reader, "foo").get(topDocs.scoreDocs[0].doc));
      }
    }
    reader.close();
    w.close();
    dir.close();
  }

  static class DVUpdateRunnable implements Runnable {

    private final int numDocs;
    private final Random random;
    private final AtomicInteger updateCount;
    private final IndexWriter w;
    private final Map<Integer, Long> values;
    private final CountDownLatch latch;

    DVUpdateRunnable(int numDocs, Random random, CountDownLatch latch, AtomicInteger updateCount, IndexWriter w, Map<Integer, Long> values) {
      this.numDocs = numDocs;
      this.random = random;
      this.latch = latch;
      this.updateCount = updateCount;
      this.w = w;
      this.values = values;
    }

    @Override
    public void run() {
      try {
        latch.await();
        while (updateCount.decrementAndGet() >= 0) {
          final int id = random.nextInt(numDocs);
          final long value = random.nextInt(20);

          synchronized (values) {
            w.updateDocValues(new Term("id", Integer.toString(id)), new NumericDocValuesField("foo", value));
            values.put(id, value);
          }

          switch (random.nextInt(10)) {
            case 0:
            case 1:
              // reopen
              DirectoryReader.open(w).close();
              break;
            case 2:
              w.forceMerge(3);
              break;
          }
        }
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

  }

  // There is tricky logic to resolve dv updates that happened while merging
  public void testConcurrentDVUpdates() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.LONG));
    iwc.setIndexSort(indexSort);
    IndexWriter w = new IndexWriter(dir, iwc);
    Map<Integer, Long> values = new HashMap<>();

    final int numDocs = atLeast(100);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      doc.add(new StringField("id", Integer.toString(i), Store.NO));
      doc.add(new NumericDocValuesField("foo", -1));
      w.addDocument(doc);
      values.put(i, -1L);
    }
    Thread[] threads = new Thread[2];
    final AtomicInteger updateCount = new AtomicInteger(atLeast(1000));
    final CountDownLatch latch = new CountDownLatch(1);
    for (int i = 0; i < threads.length; ++i) {
      Random r = new Random(random().nextLong());
      threads[i] = new Thread(new DVUpdateRunnable(numDocs, r, latch, updateCount, w, values));
    }
    for (Thread thread : threads) {
      thread.start();
    }
    latch.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    w.forceMerge(1);
    DirectoryReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);
    for (int i = 0; i < numDocs; ++i) {
      final TopDocs topDocs = searcher.search(new TermQuery(new Term("id", Integer.toString(i))), 1);
      assertEquals(1, topDocs.totalHits);
      assertEquals(values.get(i).longValue(), MultiDocValues.getNumericValues(reader, "foo").get(topDocs.scoreDocs[0].doc));
    }
    reader.close();
    w.close();
    dir.close();
  }

  public void testAddIndexes(boolean withDeletes) throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);
    final int numDocs = atLeast(100);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      doc.add(new StringField("id", Integer.toString(i), Store.NO));
      doc.add(new NumericDocValuesField("foo", random().nextInt(20)));
      w.addDocument(doc);
    }
    if (withDeletes) {
      for (int i = random().nextInt(5); i < numDocs; i += TestUtil.nextInt(random(), 1, 5)) {
        w.deleteDocuments(new Term("id", Integer.toString(i)));
      }
    }
    final IndexReader reader = w.getReader();

    Directory dir2 = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    Sort indexSort = new Sort(new SortField("foo", SortField.Type.LONG));
    iwc.setIndexSort(indexSort);
    IndexWriter w2 = new IndexWriter(dir2, iwc);

    CodecReader[] codecReaders = new CodecReader[reader.leaves().size()];
    for (int i = 0; i < codecReaders.length; ++i) {
      codecReaders[i] = (CodecReader) reader.leaves().get(i).reader();
    }
    w2.addIndexes(codecReaders);
    final IndexReader reader2 = w2.getReader();
    final IndexSearcher searcher = newSearcher(reader);
    final IndexSearcher searcher2 = newSearcher(reader2);
    for (int i = 0; i < numDocs; ++i) {
      Query query = new TermQuery(new Term("id", Integer.toString(i)));
      final TopDocs topDocs = searcher.search(query, 1);
      final TopDocs topDocs2 = searcher2.search(query, 1);
      assertEquals(topDocs.totalHits, topDocs2.totalHits);
      if (topDocs.totalHits == 1) {
        assertEquals(
            MultiDocValues.getNumericValues(reader, "foo").get(topDocs.scoreDocs[0].doc),
            MultiDocValues.getNumericValues(reader2, "foo").get(topDocs2.scoreDocs[0].doc));
      }
    }

    IOUtils.close(reader, reader2, w, w2, dir, dir2);
  }

  public void testAddIndexes() throws Exception {
    testAddIndexes(false);
  }

  public void testAddIndexesWithDeletions() throws Exception {
    testAddIndexes(true);
  }

  public void testBadSort() throws Exception {
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    IllegalArgumentException expected = expectThrows(IllegalArgumentException.class, () -> {
      iwc.setIndexSort(Sort.RELEVANCE);
    });
    assertEquals("invalid SortField type: must be one of [STRING, INT, FLOAT, LONG, DOUBLE] but got: <score>", expected.getMessage());
  }

  // you can't change the index sort on an existing index:
  public void testIllegalChangeSort() throws Exception {
    final Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    iwc.setIndexSort(new Sort(new SortField("foo", SortField.Type.LONG)));
    IndexWriter w = new IndexWriter(dir, iwc);
    w.addDocument(new Document());
    DirectoryReader.open(w).close();
    w.addDocument(new Document());
    w.forceMerge(1);
    w.close();

    final IndexWriterConfig iwc2 = new IndexWriterConfig(new MockAnalyzer(random()));
    iwc2.setIndexSort(new Sort(new SortField("bar", SortField.Type.LONG)));
    IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> {
        new IndexWriter(dir, iwc2);
    });
    String message = e.getMessage();
    assertTrue(message.contains("cannot change previous indexSort=<long: \"foo\">"));
    assertTrue(message.contains("to new indexSort=<long: \"bar\">"));
    dir.close();
  }

  static final class NormsSimilarity extends Similarity {
    
    private final Similarity in;
    
    public NormsSimilarity(Similarity in) {
      this.in = in;
    }
    
    @Override
    public long computeNorm(FieldInvertState state) {
      if (state.getName().equals("norms")) {
        return Float.floatToIntBits(state.getBoost());
      } else {
        return in.computeNorm(state);
      }
    }
    
    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
      return in.computeWeight(collectionStats, termStats);
    }
    
    @Override
    public SimScorer simScorer(SimWeight weight, LeafReaderContext context) throws IOException {
      return in.simScorer(weight, context);
    }
    
  }
  
  static final class PositionsTokenStream extends TokenStream {
    
    private final CharTermAttribute term;
    private final PayloadAttribute payload;
    private final OffsetAttribute offset;
    
    private int pos, off;
    
    public PositionsTokenStream() {
      term = addAttribute(CharTermAttribute.class);
      payload = addAttribute(PayloadAttribute.class);
      offset = addAttribute(OffsetAttribute.class);
    }
    
    @Override
    public boolean incrementToken() throws IOException {
      if (pos == 0) {
        return false;
      }
      
      clearAttributes();
      term.append("#all#");
      payload.setPayload(new BytesRef(Integer.toString(pos)));
      offset.setOffset(off, off);
      --pos;
      ++off;
      return true;
    }
    
    void setId(int id) {
      pos = id / 10 + 1;
      off = 0;
    }
  }

  public void testRandom2() throws Exception {
    int numDocs = atLeast(100);

    FieldType POSITIONS_TYPE = new FieldType(TextField.TYPE_NOT_STORED);
    POSITIONS_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    POSITIONS_TYPE.freeze();

    FieldType TERM_VECTORS_TYPE = new FieldType(TextField.TYPE_NOT_STORED);
    TERM_VECTORS_TYPE.setStoreTermVectors(true);
    TERM_VECTORS_TYPE.freeze();

    List<Document> docs = new ArrayList<>();
    for (int i=0;i<numDocs;i++) {
      int id = i * 10;
      Document doc = new Document();
      doc.add(new StringField("id", Integer.toString(id), Store.YES));
      doc.add(new StringField("docs", "#all#", Store.NO));
      PositionsTokenStream positions = new PositionsTokenStream();
      positions.setId(id);
      doc.add(new Field("positions", positions, POSITIONS_TYPE));
      doc.add(new NumericDocValuesField("numeric", id));
      TextField norms = new TextField("norms", Integer.toString(id), Store.NO);
      norms.setBoost(Float.intBitsToFloat(id));
      doc.add(norms);
      doc.add(new BinaryDocValuesField("binary", new BytesRef(Integer.toString(id))));
      doc.add(new SortedDocValuesField("sorted", new BytesRef(Integer.toString(id))));
      doc.add(new SortedSetDocValuesField("sorted_set", new BytesRef(Integer.toString(id))));
      doc.add(new SortedSetDocValuesField("sorted_set", new BytesRef(Integer.toString(id + 1))));
      doc.add(new SortedNumericDocValuesField("sorted_numeric", id));
      doc.add(new SortedNumericDocValuesField("sorted_numeric", id + 1));
      doc.add(new Field("term_vectors", Integer.toString(id), TERM_VECTORS_TYPE));
      byte[] bytes = new byte[4];
      NumericUtils.intToSortableBytes(id, bytes, 0);
      doc.add(new BinaryPoint("points", bytes));
      docs.add(doc);
    }

    // Must use the same seed for both RandomIndexWriters so they behave identically
    long seed = random().nextLong();

    // We add document alread in ID order for the first writer:
    Directory dir1 = newFSDirectory(createTempDir());
    
    IndexWriterConfig iwc1 = newIndexWriterConfig(new MockAnalyzer(random()));
    iwc1.setSimilarity(new NormsSimilarity(iwc1.getSimilarity())); // for testing norms field
    // preserve docIDs
    iwc1.setMergePolicy(newLogMergePolicy());
    if (VERBOSE) {
      System.out.println("TEST: now index pre-sorted");
    }
    RandomIndexWriter w1 = new RandomIndexWriter(new Random(seed), dir1, iwc1);
    for(Document doc : docs) {
      ((PositionsTokenStream) ((Field) doc.getField("positions")).tokenStreamValue()).setId(Integer.parseInt(doc.get("id")));
      w1.addDocument(doc);
    }

    // We shuffle documents, but set index sort, for the second writer:
    Directory dir2 = newFSDirectory(createTempDir());
    
    IndexWriterConfig iwc2 = newIndexWriterConfig(new MockAnalyzer(random()));
    iwc2.setSimilarity(new NormsSimilarity(iwc2.getSimilarity())); // for testing norms field

    Sort sort = new Sort(new SortField("numeric", SortField.Type.INT));
    iwc2.setIndexSort(sort);

    Collections.shuffle(docs, random());
    if (VERBOSE) {
      System.out.println("TEST: now index with index-time sorting");
    }
    RandomIndexWriter w2 = new RandomIndexWriter(new Random(seed), dir2, iwc2);
    int count = 0;
    int commitAtCount = TestUtil.nextInt(random(), 1, numDocs-1);
    for(Document doc : docs) {
      ((PositionsTokenStream) ((Field) doc.getField("positions")).tokenStreamValue()).setId(Integer.parseInt(doc.get("id")));
      if (count++ == commitAtCount) {
        // Ensure forceMerge really does merge
        w2.commit();
      }
      w2.addDocument(doc);
    }
    w2.forceMerge(1);

    DirectoryReader r1 = w1.getReader();
    DirectoryReader r2 = w2.getReader();
    assertEquals(sort, getOnlyLeafReader(r2).getIndexSort());
    assertReaderEquals("left: sorted by hand; right: sorted by Lucene", r1, r2);
    IOUtils.close(w1, w2, r1, r2, dir1, dir2);
  }

  private static final class RandomDoc {
    public final int id;
    public final int intValue;
    public final long longValue;
    public final float floatValue;
    public final double doubleValue;
    public final byte[] bytesValue;
    // nocommit postings, points, term vectors

    public RandomDoc(int id) {
      this.id = id;
      intValue = random().nextInt();
      longValue = random().nextLong();
      floatValue = random().nextFloat();
      doubleValue = random().nextDouble();
      bytesValue = new byte[TestUtil.nextInt(random(), 1, 50)];
      random().nextBytes(bytesValue);
    }
  }

  private static Sort randomSort() {
    int numFields = TestUtil.nextInt(random(), 1, 3);
    SortField[] sortFields = new SortField[numFields];
    for(int i=0;i<numFields-1;i++) {
      boolean reversed = random().nextBoolean();
      SortField sortField;
      switch(random().nextInt(5)) {
      case 0:
        sortField = new SortField("int", SortField.Type.INT, reversed);
        if (random().nextBoolean()) {
          sortField.setMissingValue(random().nextInt());
        }
        break;
      case 1:
        sortField = new SortField("long", SortField.Type.LONG, reversed);
        if (random().nextBoolean()) {
          sortField.setMissingValue(random().nextLong());
        }
        break;
      case 2:
        sortField = new SortField("float", SortField.Type.FLOAT, reversed);
        if (random().nextBoolean()) {
          sortField.setMissingValue(random().nextFloat());
        }
        break;
      case 3:
        sortField = new SortField("double", SortField.Type.DOUBLE, reversed);
        if (random().nextBoolean()) {
          sortField.setMissingValue(random().nextDouble());
        }
        break;
      case 4:
        sortField = new SortField("bytes", SortField.Type.STRING, reversed);
        if (random().nextBoolean()) {
          sortField.setMissingValue(SortField.STRING_LAST);
        }
        break;
      default:
        throw new AssertionError();
      }
      sortFields[i] = sortField;
    }

    // tie-break by id:
    sortFields[numFields-1] = new SortField("id", SortField.Type.INT);

    return new Sort(sortFields);
  }

  // pits index time sorting against query time sorting
  public void testRandom3() throws Exception {
    int numDocs;
    if (TEST_NIGHTLY) {
      numDocs = atLeast(100000);
    } else {
      numDocs = atLeast(1000);
    }
    List<RandomDoc> docs = new ArrayList<>();

    Sort sort = randomSort();
    if (VERBOSE) {
      System.out.println("TEST: numDocs=" + numDocs + " use sort=" + sort);
    }

    // no index sorting, all search-time sorting:
    Directory dir1 = newFSDirectory(createTempDir());
    IndexWriterConfig iwc1 = newIndexWriterConfig(new MockAnalyzer(random()));
    IndexWriter w1 = new IndexWriter(dir1, iwc1);

    // use index sorting:
    Directory dir2 = newFSDirectory(createTempDir());
    IndexWriterConfig iwc2 = newIndexWriterConfig(new MockAnalyzer(random()));
    iwc2.setIndexSort(sort);
    IndexWriter w2 = new IndexWriter(dir2, iwc2);

    Set<Integer> toDelete = new HashSet<>();

    double deleteChance = random().nextDouble();

    for(int id=0;id<numDocs;id++) {
      RandomDoc docValues = new RandomDoc(id);
      docs.add(docValues);
      if (VERBOSE) {
        System.out.println("TEST: doc id=" + id);
        System.out.println("  int=" + docValues.intValue);
        System.out.println("  long=" + docValues.longValue);
        System.out.println("  float=" + docValues.floatValue);
        System.out.println("  double=" + docValues.doubleValue);
        System.out.println("  bytes=" + new BytesRef(docValues.bytesValue));
      }

      Document doc = new Document();
      doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
      doc.add(new NumericDocValuesField("id", id));
      doc.add(new NumericDocValuesField("int", docValues.intValue));
      doc.add(new NumericDocValuesField("long", docValues.longValue));
      doc.add(new DoubleDocValuesField("double", docValues.doubleValue));
      doc.add(new FloatDocValuesField("float", docValues.floatValue));
      doc.add(new SortedDocValuesField("bytes", new BytesRef(docValues.bytesValue)));
      w1.addDocument(doc);
      w2.addDocument(doc);
      if (random().nextDouble() < deleteChance) {
        toDelete.add(id);
      }
    }
    for(int id : toDelete) {
      w1.deleteDocuments(new Term("id", Integer.toString(id)));
      w2.deleteDocuments(new Term("id", Integer.toString(id)));
    }
    DirectoryReader r1 = DirectoryReader.open(w1);
    IndexSearcher s1 = newSearcher(r1);

    if (random().nextBoolean()) {
      int maxSegmentCount = TestUtil.nextInt(random(), 1, 5);
      if (VERBOSE) {
        System.out.println("TEST: now forceMerge(" + maxSegmentCount + ")");
      }
      w2.forceMerge(maxSegmentCount);
    }

    DirectoryReader r2 = DirectoryReader.open(w2);
    IndexSearcher s2 = newSearcher(r2);

    /*
    System.out.println("TEST: full index:");
    SortedDocValues docValues = MultiDocValues.getSortedValues(r2, "bytes");
    for(int i=0;i<r2.maxDoc();i++) {
      System.out.println("  doc " + i + " id=" + r2.document(i).get("id") + " bytes=" + docValues.get(i));
    }
    */

    for(int iter=0;iter<100;iter++) {
      int numHits = TestUtil.nextInt(random(), 1, numDocs);
      if (VERBOSE) {
        System.out.println("TEST: iter=" + iter + " numHits=" + numHits);
      }

      TopFieldCollector c1 = TopFieldCollector.create(sort, numHits, true, true, true);
      s1.search(new MatchAllDocsQuery(), c1);
      TopDocs hits1 = c1.topDocs();

      TopFieldCollector c2 = TopFieldCollector.create(sort, numHits, true, true, true);
      EarlyTerminatingSortingCollector c3 = new EarlyTerminatingSortingCollector(c2, sort, numHits);
      s2.search(new MatchAllDocsQuery(), c3);

      TopDocs hits2 = c2.topDocs();

      if (VERBOSE) {
        System.out.println("  topDocs query-time sort: totalHits=" + hits1.totalHits);
        for(ScoreDoc scoreDoc : hits1.scoreDocs) {
          System.out.println("    " + scoreDoc.doc);
        }
        System.out.println("  topDocs index-time sort: totalHits=" + hits2.totalHits);
        for(ScoreDoc scoreDoc : hits2.scoreDocs) {
          System.out.println("    " + scoreDoc.doc);
        }
      }

      assertTrue(hits2.totalHits <= hits1.totalHits);
      assertEquals(hits2.scoreDocs.length, hits1.scoreDocs.length);
      for(int i=0;i<hits2.scoreDocs.length;i++) {
        ScoreDoc hit1 = hits1.scoreDocs[i];
        ScoreDoc hit2 = hits2.scoreDocs[i];
        assertEquals(r1.document(hit1.doc).get("id"), r2.document(hit2.doc).get("id"));
        assertEquals(((FieldDoc) hit1).fields, ((FieldDoc) hit2).fields);
      }
    }

    IOUtils.close(r1, r2, w1, w2, dir1, dir2);
  }
}
