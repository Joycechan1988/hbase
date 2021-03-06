/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.encoding.HFileBlockDefaultEncodingContext;
import org.apache.hadoop.hbase.io.encoding.HFileBlockEncodingContext;
import org.apache.hadoop.hbase.util.ChecksumType;
import org.apache.hadoop.hbase.util.test.RedundantKVGenerator;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
@Category(SmallTests.class)
public class TestHFileDataBlockEncoder {
  private Configuration conf;
  private final HBaseTestingUtility TEST_UTIL =
      new HBaseTestingUtility();
  private HFileDataBlockEncoderImpl blockEncoder;
  private RedundantKVGenerator generator = new RedundantKVGenerator();
  private boolean includesMemstoreTS;

  /**
   * Create test for given data block encoding configuration.
   * @param blockEncoder What kind of encoding policy will be used.
   */
  public TestHFileDataBlockEncoder(HFileDataBlockEncoderImpl blockEncoder,
      boolean includesMemstoreTS) {
    this.blockEncoder = blockEncoder;
    this.includesMemstoreTS = includesMemstoreTS;
    System.err.println("On-disk encoding: " + blockEncoder.getEncodingOnDisk()
        + ", in-cache encoding: " + blockEncoder.getEncodingInCache()
        + ", includesMemstoreTS: " + includesMemstoreTS);
  }

  /**
   * Preparation before JUnit test.
   */
  @Before
  public void setUp() {
    conf = TEST_UTIL.getConfiguration();
  }

  /**
   * Test putting and taking out blocks into cache with different
   * encoding options.
   */
  @Test
  public void testEncodingWithCache() {
    testEncodingWithCacheInternals(false);
    testEncodingWithCacheInternals(true);
  }

  private void testEncodingWithCacheInternals(boolean useTag) {
    HFileBlock block = getSampleHFileBlock(useTag);
    LruBlockCache blockCache =
        new LruBlockCache(8 * 1024 * 1024, 32 * 1024);
    HFileBlock cacheBlock = blockEncoder.diskToCacheFormat(block, false);
    BlockCacheKey cacheKey = new BlockCacheKey("test", 0);
    blockCache.cacheBlock(cacheKey, cacheBlock);

    HeapSize heapSize = blockCache.getBlock(cacheKey, false, false);
    assertTrue(heapSize instanceof HFileBlock);

    HFileBlock returnedBlock = (HFileBlock) heapSize;;

    if (blockEncoder.getEncodingInCache() ==
        DataBlockEncoding.NONE) {
      assertEquals(block.getBufferWithHeader(),
          returnedBlock.getBufferWithHeader());
    } else {
      if (BlockType.ENCODED_DATA != returnedBlock.getBlockType()) {
        System.out.println(blockEncoder);
      }
      assertEquals(BlockType.ENCODED_DATA, returnedBlock.getBlockType());
    }
  }

  /** Test for HBASE-5746. */
  @Test
  public void testHeaderSizeInCacheWithoutChecksum() throws Exception {
    testHeaderSizeInCacheWithoutChecksumInternals(false);
    testHeaderSizeInCacheWithoutChecksumInternals(true);
  }

  private void testHeaderSizeInCacheWithoutChecksumInternals(boolean useTags) throws IOException {
    int headerSize = HConstants.HFILEBLOCK_HEADER_SIZE_NO_CHECKSUM;
    // Create some KVs and create the block with old-style header.
    ByteBuffer keyValues = RedundantKVGenerator.convertKvToByteBuffer(
        generator.generateTestKeyValues(60, useTags), includesMemstoreTS);
    int size = keyValues.limit();
    ByteBuffer buf = ByteBuffer.allocate(size + headerSize);
    buf.position(headerSize);
    keyValues.rewind();
    buf.put(keyValues);
    HFileContext meta = new HFileContextBuilder().withHBaseCheckSum(false)
                        .withIncludesMvcc(includesMemstoreTS)
                        .withIncludesTags(useTags)
                        .withBlockSize(0)
                        .withChecksumType(ChecksumType.NULL)
                        .build();
    HFileBlock block = new HFileBlock(BlockType.DATA, size, size, -1, buf,
        HFileBlock.FILL_HEADER, 0,
        0, meta);
    HFileBlock cacheBlock = blockEncoder
        .diskToCacheFormat(createBlockOnDisk(block, useTags), false);
    assertEquals(headerSize, cacheBlock.getDummyHeaderForVersion().length);
  }

  private HFileBlock createBlockOnDisk(HFileBlock block, boolean useTags) throws IOException {
    int size;
    HFileBlockEncodingContext context = new HFileBlockDefaultEncodingContext(
        blockEncoder.getEncodingOnDisk(),
        HConstants.HFILEBLOCK_DUMMY_HEADER, block.getHFileContext());
    context.setDummyHeader(block.getDummyHeaderForVersion());
    blockEncoder.beforeWriteToDisk(block.getBufferWithoutHeader(), context, block.getBlockType());
    byte[] encodedBytes = context.getUncompressedBytesWithHeader();
    size = encodedBytes.length - block.getDummyHeaderForVersion().length;
    return new HFileBlock(context.getBlockType(), size, size, -1,
            ByteBuffer.wrap(encodedBytes), HFileBlock.FILL_HEADER, 0,
            block.getOnDiskDataSizeWithHeader(), block.getHFileContext());
  }

  /**
   * Test writing to disk.
   * @throws IOException
   */
  @Test
  public void testEncodingWritePath() throws IOException {
    testEncodingWritePathInternals(false);
    testEncodingWritePathInternals(true);
  }

  private void testEncodingWritePathInternals(boolean useTag) throws IOException {
    // usually we have just block without headers, but don't complicate that
    HFileBlock block = getSampleHFileBlock(useTag);
    HFileBlock blockOnDisk = createBlockOnDisk(block, useTag);

    if (blockEncoder.getEncodingOnDisk() !=
        DataBlockEncoding.NONE) {
      assertEquals(BlockType.ENCODED_DATA, blockOnDisk.getBlockType());
      assertEquals(blockEncoder.getEncodingOnDisk().getId(),
          blockOnDisk.getDataBlockEncodingId());
    } else {
      assertEquals(BlockType.DATA, blockOnDisk.getBlockType());
    }
  }

  /**
   * Test converting blocks from disk to cache format.
   */
  @Test
  public void testEncodingReadPath() {
    testEncodingReadPathInternals(false);
    testEncodingReadPathInternals(true);
  }

  private void testEncodingReadPathInternals(boolean useTag) {
    HFileBlock origBlock = getSampleHFileBlock(useTag);
    blockEncoder.diskToCacheFormat(origBlock, false);
  }

  private HFileBlock getSampleHFileBlock(boolean useTag) {
    ByteBuffer keyValues = RedundantKVGenerator.convertKvToByteBuffer(
        generator.generateTestKeyValues(60, useTag), includesMemstoreTS);
    int size = keyValues.limit();
    ByteBuffer buf = ByteBuffer.allocate(size + HConstants.HFILEBLOCK_HEADER_SIZE);
    buf.position(HConstants.HFILEBLOCK_HEADER_SIZE);
    keyValues.rewind();
    buf.put(keyValues);
    HFileContext meta = new HFileContextBuilder()
                        .withIncludesMvcc(includesMemstoreTS)
                        .withIncludesTags(useTag)
                        .withHBaseCheckSum(true)
                        .withCompressionAlgo(Algorithm.NONE)
                        .withBlockSize(0)
                        .withChecksumType(ChecksumType.NULL)
                        .build();
    HFileBlock b = new HFileBlock(BlockType.DATA, size, size, -1, buf,
        HFileBlock.FILL_HEADER, 0, 
         0, meta);
    return b;
  }

  /**
   * @return All possible data block encoding configurations
   */
  @Parameters
  public static Collection<Object[]> getAllConfigurations() {
    List<Object[]> configurations =
        new ArrayList<Object[]>();

    for (DataBlockEncoding diskAlgo : DataBlockEncoding.values()) {
      for (DataBlockEncoding cacheAlgo : DataBlockEncoding.values()) {
        if (diskAlgo != cacheAlgo && diskAlgo != DataBlockEncoding.NONE) {
          // We allow (1) the same encoding on disk and in cache, and
          // (2) some encoding in cache but no encoding on disk (for testing).
          continue;
        }
        for (boolean includesMemstoreTS : new boolean[] {false, true}) {
          configurations.add(new Object[] {
              new HFileDataBlockEncoderImpl(diskAlgo, cacheAlgo),
              new Boolean(includesMemstoreTS)});
        }
      }
    }

    return configurations;
  }
}
