/**
 * Copyright 2020 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.store;

import com.github.ambry.config.StoreConfig;
import com.github.ambry.utils.Time;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codehaus.jackson.map.ObjectMapper;


/**
 * HybridCompactionPolicy will run compaction with StatsBasedCompactionPolicy more frequently and with CompactAllPolicy
 * one time out of storeCompactionPolicySwitchPeriod.
 */
public class HybridCompactionPolicy implements CompactionPolicy {
  private final Time time;
  private final StoreConfig storeConfig;
  private static final Logger logger = LoggerFactory.getLogger(HybridCompactionPolicy.class);
  private final Map<String, CompactionPolicySwitchInfo> blobToCompactionPolicySwitchInfoMap;
  private final ObjectMapper mapper = new ObjectMapper();
  private static final String COMPACT_POLICY_INFO_PATH = File.separator + "compactionPolicyInfo.json";
  private static final int INIT_COMPACT_ALL_TIME = 0;
  private static final int INIT_COUNTER_VALUE = 0;

  HybridCompactionPolicy(StoreConfig storeConfig, Time time) {
    this.storeConfig = storeConfig;
    this.time = time;
    this.blobToCompactionPolicySwitchInfoMap = new HashMap<>();
  }

  /**
   * @param totalCapacity Total capacity of the {@link BlobStore}
   * @param usedCapacity Used capacity of the {@link BlobStore}
   * @param segmentCapacity Segment capacity of a {@link LogSegment}
   * @param segmentHeaderSize Segment header size of a {@link LogSegment}
   * @param logSegmentsNotInJournal {@link List<String> } of log segment names which has non overlapping entries with
   *                                {@link Journal}
   * @param blobStoreStats {@link BlobStoreStats} pertaining to the {@link BlobStore} for which
   * {@link CompactionDetails} are requested
   * @param dataDir the dir to store {@link CompactionPolicySwitchInfo}
   * @return {@link CompactAllPolicy} or {@link StatsBasedCompactionPolicy}'s {@link CompactionDetails} depends on the switching rules.
   */
  @Override
  public CompactionDetails getCompactionDetails(long totalCapacity, long usedCapacity, long segmentCapacity,
      long segmentHeaderSize, List<String> logSegmentsNotInJournal, BlobStoreStats blobStoreStats, String dataDir)
      throws StoreException {
    String storeId = blobStoreStats.getStoreId();
    CompactionPolicySwitchInfo compactionPolicySwitchInfo = getAndRecoverCompactionPolicySwitchInfo(storeId, dataDir);
    CompactionPolicy selectCompactionPolicy =
        selectCompactionPolicyAndUpdateCompactionPolicySwitchInfo(compactionPolicySwitchInfo);
    logger.info("Current compaction policy  is : {}", selectCompactionPolicy);
    backUpCompactionPolicyInfo(dataDir, storeId, compactionPolicySwitchInfo);
    compactionPolicySwitchInfo.getCompactionPolicyCounter().increment();
    return selectCompactionPolicy.getCompactionDetails(totalCapacity, usedCapacity, segmentCapacity, segmentHeaderSize,
        logSegmentsNotInJournal, blobStoreStats, dataDir);
  }

  /**
   * Get and Recover the CompactionPolicySwitchInfo from file.
   * @param dataDir The directory to store the file.
   * @param storeId id of the BlobStore
   * @return {@link CompactionPolicySwitchInfo} gets from map or recover from file if needed.
   */
  private CompactionPolicySwitchInfo getAndRecoverCompactionPolicySwitchInfo(String storeId, String dataDir) {
    if (!blobToCompactionPolicySwitchInfoMap.containsKey(storeId)) {
      File file = new File(Paths.get(dataDir, COMPACT_POLICY_INFO_PATH).toString());
      if (file.exists()) {
        CompactionPolicySwitchInfo compactionPolicySwitchInfo = recoverCompactionPolicySwitchInfo(file);
        blobToCompactionPolicySwitchInfoMap.put(storeId, compactionPolicySwitchInfo);
      } else {
        blobToCompactionPolicySwitchInfoMap.put(storeId,
            new CompactionPolicySwitchInfo(new CompactionPolicyCounter(storeConfig.storeCompactionPolicySwitchPeriod),
                INIT_COMPACT_ALL_TIME));
      }
    }
    return blobToCompactionPolicySwitchInfoMap.get(storeId);
  }

  /**
   * Recover the {@link CompactionPolicySwitchInfo} from backup file.
   * {
   *   "compactionPolicyCounter" : {
   *     "storeCompactionPolicySwitchPeriod" : 3,
   *     "counter" : 1,
   *     "value" : 1
   *   },
   *   "lastCompactAllTime" : 1593463435900
   * }
   * @param file the backup file stores {@link CompactionPolicySwitchInfo}
   */
  private CompactionPolicySwitchInfo recoverCompactionPolicySwitchInfo(File file) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(file, CompactionPolicySwitchInfo.class);
    } catch (IOException e) {
      logger.error("Could not deserialize file : {} into {} Object", file, CompactionPolicySwitchInfo.class.getName());
      return new CompactionPolicySwitchInfo(new CompactionPolicyCounter(storeConfig.storeCompactionPolicySwitchPeriod),
          INIT_COMPACT_ALL_TIME);
    }
  }

  /**
   * Selects which compaction policy to use for current compaction cycle.
   * @return {@link CompactionPolicy} to use for current compaction cycle(Selects between {@link StatsBasedCompactionPolicy} and {@link CompactAllPolicy}).
   */
  CompactionPolicy selectCompactionPolicyAndUpdateCompactionPolicySwitchInfo(
      CompactionPolicySwitchInfo compactionPolicySwitchInfo) {
    if (compactionPolicySwitchInfo == null) {
      logger.trace("CompactionPolicySwitchInfo is null");
      return new StatsBasedCompactionPolicy(storeConfig, time);
    }
    if (readyToTriggerCompactionAllPolicy(compactionPolicySwitchInfo)) {
      logger.trace("Return CompactAllPolicy this round");
      updateCompactionInfo(compactionPolicySwitchInfo);
      return new CompactAllPolicy(storeConfig, time);
    } else {
      if (compactionPolicySwitchInfo.getCompactionPolicyCounter() == null) {
        logger.trace("Counter is null");
      } else {
        logger.trace("Return StatsBasedCompactionPolicy this round");
      }
      return new StatsBasedCompactionPolicy(storeConfig, time);
    }
  }

  /**
   * Determine which compactionPolicy to use for current compaction cycle.
   * @param compactionPolicySwitchInfo the info to determine which {@link CompactionPolicy} to use this round.
   * @return {@code true} if the counter value equals to 0 or it's storeCompactionPolicySwitchPeriod days past the start time of CompactAllPolicy.
   */
  private boolean readyToTriggerCompactionAllPolicy(CompactionPolicySwitchInfo compactionPolicySwitchInfo) {
    return compactionPolicySwitchInfo.getCompactionPolicyCounter() != null
        && compactionPolicySwitchInfo.getCompactionPolicyCounter().getValue() == 0 ||
        compactionPolicySwitchInfo.getLastCompactAllTime() + TimeUnit.DAYS.toMillis(
            storeConfig.storeCompactionPolicySwitchPeriod) <= System.currentTimeMillis();
  }

  /**
   * Update the {@link CompactionPolicySwitchInfo} before the start of {@link CompactAllPolicy}
   * @param compactionPolicySwitchInfo the info to determine which {@link CompactionPolicy} to use this round.
   */
  private void updateCompactionInfo(CompactionPolicySwitchInfo compactionPolicySwitchInfo) {
    compactionPolicySwitchInfo.setLastCompactAllTime(System.currentTimeMillis());
    compactionPolicySwitchInfo.getCompactionPolicyCounter().setValue(INIT_COUNTER_VALUE);
  }

  /**
   * @return blobToCompactionPolicySwitchInfoMap which key is storeId and value is {@link CompactionPolicySwitchInfo}
   */
  Map<String, CompactionPolicySwitchInfo> getBlobToCompactionPolicySwitchInfoMap() {
    return this.blobToCompactionPolicySwitchInfoMap;
  }

  /**
   * Back up {@link CompactionPolicySwitchInfo} in Json format for each {@link BlobStore}
   * @param dataDir The directory to store the file.
   * @param storeId id of the BlobStore
   * @param compactionPolicySwitchInfo the info to determine which {@link CompactionPolicy} to use this round.
   */
  private void backUpCompactionPolicyInfo(String dataDir, String storeId,
      CompactionPolicySwitchInfo compactionPolicySwitchInfo) {
    if (dataDir != null && !dataDir.isEmpty()) {
      File tempFile = new File(Paths.get(dataDir, COMPACT_POLICY_INFO_PATH).toString());
      try {
        tempFile.createNewFile();
        mapper.defaultPrettyPrintingWriter().writeValue(tempFile, compactionPolicySwitchInfo);
      } catch (IOException e) {
        logger.error("Exception while store compaction policy info for local report. Output file path - {}",
            tempFile.getAbsolutePath(), e);
      }
    }
  }
}


