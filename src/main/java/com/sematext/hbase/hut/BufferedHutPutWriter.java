/**
 * Copyright 2010 Sematext International
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sematext.hbase.hut;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;

/**
 * Buffers puts during writing (client-side) and performs updates processing during flushes
 */
public class BufferedHutPutWriter {
  private final HTable hTable;
  private final UpdateProcessor updateProcessor;
  // buffer is similar to LRU cache
  // TODO: consider setting max timeout for record 15group being in the buffer to prevent
  //       some records stuck for too long
  // TODO: consider removing not just lru but old *and* where we can compact more
  private final LinkedHashMap<ByteArrayWrapper, List<Put>> buffer;
  // Can be converted to local variable, but we want to reuse processingResult instance
  private HutResultScanner.UpdateProcessingResultImpl processingResult =
          new HutResultScanner.UpdateProcessingResultImpl();

  // some stats
  private long queuedRecordsCount;
  private long writtenRecordsCount;

  // TODO: do these fields really belong to this class and not to the buffer class?
  private final int maxBufferSize;
  private int bufferedCount;

  public BufferedHutPutWriter(HTable hTable, UpdateProcessor updateProcessor, int bufferSize) {
    this.hTable = hTable;
    this.updateProcessor = updateProcessor;
      // Notes:
      // * initial capacity is just an estimate TODO: allow client code (which uses writer) control it
      // * using insertion order so that records don't stuck for long time in the buffer
    this.buffer = new LinkedHashMap<ByteArrayWrapper, List<Put>>(bufferSize / 3, 1.0f, false);
    this.bufferedCount = 0;
    this.maxBufferSize = bufferSize;
    resetStats();
  }

  public void write(Put put) {
    queuedRecordsCount++;
    
    // think over reusing object instance
    ByteArrayWrapper key = new ByteArrayWrapper(HutRowKeyUtil.getOriginalKey(put.getRow()));
    List<Put> puts = buffer.get(key);
    if (puts == null) {
      puts = new ArrayList<Put>();
      buffer.put(key, puts);
    }

    puts.add(put);
    bufferedCount++;

    flushBufferPartIfNeeded();
  }

  public void flush() {
    for (List<Put> group : buffer.values()) {
      processGroupAndWrite(group);
    }

    buffer.clear();

    bufferedCount = 0;
  }

  public void resetStats() {
    queuedRecordsCount = 0;
    writtenRecordsCount = 0;
  }

  public long getQueuedRecordsCount() {
    return queuedRecordsCount;
  }

  public long getWrittenRecordsCount() {
    return writtenRecordsCount;
  }

  private void flushBufferPartIfNeeded() {
    boolean removeFromBuffer = bufferedCount > maxBufferSize;
    // TODO: is it safe to flush here?
    if (removeFromBuffer) {
      // TODO: is there a way to get & delete oldest record "in-place"?
      List<Put> eldest = buffer.values().iterator().next();
      // eldest and eldestKey in sync since using LinkedHashMap
      ByteArrayWrapper eldestKey = buffer.keySet().iterator().next();
      processGroupAndWrite(eldest);
      buffer.remove(eldestKey);
      bufferedCount -= eldest.size();
    }
  }

  private void processGroupAndWrite(List<Put> list) {
    Put first = list.get(0);
    if (list.size() > 1) {
      // TODO: do we need to place this into result by default *always*? May be only
      // when user code didn't place anything? Also see other places
      processingResult.init(first.getRow());
      List<Result> records = new ArrayList<Result>();
      for (Put put : list) {
        records.add(HTableUtil.convert(put));
      }
      updateProcessor.process(records, processingResult);

      try {
        Put put = HutResultScanner.createPutWithProcessedResult(processingResult.getResult(),
                first.getRow(), list.get(list.size() - 1).getRow());
        put(put);
      } catch (IOException e) {
        throw new RuntimeException("Error during writing processed Puts into HBase table", e);
      }
    } else {
      try {
        put(first);
      } catch (IOException e) {
        throw new RuntimeException("Error during writing Puts into HBase table", e);
      }
    }
  }

  private void put(Put put) throws IOException {
    writtenRecordsCount++;
    writeInternal(put);
  }

  protected void writeInternal(Put put) throws IOException {
    hTable.put(put);
  }
}
