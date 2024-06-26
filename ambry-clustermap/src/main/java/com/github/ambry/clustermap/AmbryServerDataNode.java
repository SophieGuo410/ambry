/*
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
 *
 */

package com.github.ambry.clustermap;

import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.network.Port;
import com.github.ambry.network.PortType;
import com.github.ambry.utils.Utils;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.github.ambry.clustermap.ClusterMapSnapshotConstants.*;
import static com.github.ambry.clustermap.ClusterMapUtils.*;


/**
 *
 * This implementation of {@link AmbryDataNode} represents a standard ambry-server host with physical disks.
 */
public class AmbryServerDataNode extends AmbryDataNode {
  private final String rackId;
  private final long xid;
  private final List<String> sslEnabledDataCenters;
  private final boolean enableHttp2Replication;
  private final ClusterManagerQueryHelper<AmbryReplica, AmbryDisk, AmbryPartition, AmbryDataNode>
      clusterManagerQueryHelper;

  /**
   * Instantiate an {@link AmbryServerDataNode}.
   * @param dataCenterName the name of the dataCenter associated with this data node.
   * @param clusterMapConfig the {@link ClusterMapConfig} to use.
   * @param hostName the hostName identifying this data node.
   * @param portNum the port identifying this data node.
   * @param rackId the rack Id associated with this data node (may be null).
   * @param sslPortNum the ssl port associated with this data node (may be null).
   * @param http2PortNumber the http2 ssl port associated with this data node (may be null).
   * @param xid the xid associated with this data node.
   * @param clusterManagerQueryHelper the {@link ClusterManagerQueryHelper} to use
   * @throws Exception if there is an exception in instantiating the {@link ResourceStatePolicy}
   */
  AmbryServerDataNode(String dataCenterName, ClusterMapConfig clusterMapConfig, String hostName, int portNum,
      String rackId, Integer sslPortNum, Integer http2PortNumber, long xid,
      ClusterManagerQueryHelper<AmbryReplica, AmbryDisk, AmbryPartition, AmbryDataNode> clusterManagerQueryHelper)
      throws Exception {
    super(dataCenterName, clusterMapConfig, hostName, portNum, sslPortNum, http2PortNumber);
    this.rackId = rackId;
    this.xid = xid;
    this.sslEnabledDataCenters = Utils.splitString(clusterMapConfig.clusterMapSslEnabledDatacenters, ",");
    this.enableHttp2Replication = clusterMapConfig.clusterMapEnableHttp2Replication;
    this.clusterManagerQueryHelper = clusterManagerQueryHelper;
    validateHostName(clusterMapConfig.clusterMapResolveHostnames, hostName);
    validatePorts(plainTextPort, sslPort, http2Port, sslEnabledDataCenters.contains(dataCenterName));
  }

  public AmbryServerDataNode(DataNodeId node, ClusterMapConfig config) {
    super((DataNode) node);
    rackId = node.getRackId();
    xid = node.getXid();
    sslEnabledDataCenters = Utils.splitString(config.clusterMapSslEnabledDatacenters, ",");
    enableHttp2Replication = config.clusterMapEnableHttp2Replication;
    clusterManagerQueryHelper = null;
  }

  @Override
  public Port getPortToConnectTo() {
    if (enableHttp2Replication) {
      return http2Port;
    }
    return sslEnabledDataCenters.contains(getDatacenterName()) ? sslPort : plainTextPort;
  }

  @Override
  public String getRackId() {
    return rackId;
  }

  @Override
  public long getXid() {
    return xid;
  }

  @Override
  public List<DiskId> getDiskIds() {
    return new ArrayList<>(clusterManagerQueryHelper.getDisks(this));
  }

  @Override
  public JSONObject getSnapshot() {
    JSONObject snapshot = new JSONObject();
    snapshot.put(DATA_NODE_HOSTNAME, getHostname());
    snapshot.put(DATA_NODE_DATACENTER, getDatacenterName());
    snapshot.put(DATA_NODE_SSL_ENABLED_DATACENTERS, new JSONArray(sslEnabledDataCenters));
    JSONObject portsJson = new JSONObject();
    portsJson.put(PortType.PLAINTEXT.name(), getPort());
    if (hasSSLPort()) {
      portsJson.put(PortType.SSL.name(), getSSLPort());
    }
    if (hasHttp2Port()) {
      portsJson.put(PortType.HTTP2.name(), getHttp2Port());
    }
    portsJson.put(DATA_NODE_PORT_CONNECT_TO, getPortToConnectTo().getPort());
    snapshot.put(DATA_NODE_PORTS, portsJson);
    snapshot.put(DATA_NODE_RACK_ID, getRackId());
    snapshot.put(DATA_NODE_XID, getXid());
    snapshot.put(LIVENESS, getLiveness());
    JSONArray disksJson = new JSONArray();
    clusterManagerQueryHelper.getDisks(this).forEach(disk -> disksJson.put(disk.getSnapshot()));
    snapshot.put(DATA_NODE_DISKS, disksJson);
    return snapshot;
  }

  @Override
  public String toString() {
    return "DataNode[" + getHostname() + ":" + getPort() + "]";
  }
}
