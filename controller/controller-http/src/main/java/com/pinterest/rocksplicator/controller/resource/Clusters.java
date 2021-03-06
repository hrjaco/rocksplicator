/*
 * Copyright 2017 Pinterest, Inc.
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

package com.pinterest.rocksplicator.controller.resource;

import com.pinterest.rocksplicator.controller.TaskBase;
import com.pinterest.rocksplicator.controller.TaskQueue;
import com.pinterest.rocksplicator.controller.bean.ClusterBean;
import com.pinterest.rocksplicator.controller.bean.HostBean;
import com.pinterest.rocksplicator.controller.config.ConfigParser;
import com.pinterest.rocksplicator.controller.tasks.AddHostTask;
import com.pinterest.rocksplicator.controller.tasks.ConfigCheckTask;
import com.pinterest.rocksplicator.controller.tasks.HealthCheckTask;
import com.pinterest.rocksplicator.controller.tasks.LoadSSTTask;
import com.pinterest.rocksplicator.controller.tasks.LoggingTask;
import com.pinterest.rocksplicator.controller.tasks.PromoteTask;
import com.pinterest.rocksplicator.controller.tasks.RebalanceTask;
import com.pinterest.rocksplicator.controller.tasks.RemoveHostTask;
import com.pinterest.rocksplicator.controller.Utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import org.apache.curator.framework.CuratorFramework;
import org.eclipse.jetty.http.HttpStatus;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author Ang Xu (angxu@pinterest.com)
 */
@Path("/v1/clusters")
public class Clusters {
  private static final Logger LOG = LoggerFactory.getLogger(Clusters.class);

  private final String zkPath;
  private final CuratorFramework zkClient;
  private final TaskQueue taskQueue;

  public Clusters(String zkPath,
                  CuratorFramework zkClient,
                  TaskQueue taskQueue) {
    this.zkPath = zkPath;
    this.zkClient = zkClient;
    this.taskQueue = taskQueue;
  }

  /**
   * Retrieves cluster information by cluster name.
   *
   * @param clusterName name of the cluster
   * @return            ClusterBean
   */
  @GET
  @Path("/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response get(@PathParam("clusterName") String clusterName) {
    final ClusterBean clusterBean;
    try {
      clusterBean = checkExistenceAndGetClusterBean(clusterName);
    } catch (Exception e) {
      String message = String.format("Failed to read from zookeeper: %s", e);
      LOG.error(message);
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500, ImmutableMap.of("message", message));
    }
    if (clusterBean == null) {
      String message = String.format("Znode %s does not exist or failed to parse config", clusterName);
      return Utils.buildResponse(HttpStatus.NOT_FOUND_404, ImmutableMap.of("message", message));
    }
    return Utils.buildResponse(HttpStatus.OK_200, clusterBean);
  }

  /**
   * Gets all clusters managed by the controller.
   *
   * @return a list of {@link ClusterBean}
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getAll(@QueryParam("verbose") Optional<Boolean> verbose) {
    final Set<String> clusters = taskQueue.getAllClusters();
    if (!verbose.isPresent() || verbose.get().equals(Boolean.FALSE)) {
      return Utils.buildResponse(HttpStatus.OK_200, clusters);
    }
    final List<ClusterBean> clusterBeans = new ArrayList<>(clusters.size());
    try {
      for (String cluster : clusters) {
        ClusterBean clusterBean = checkExistenceAndGetClusterBean(cluster);
        if (clusterBean != null) {
          clusterBeans.add(clusterBean);
        }
      }
    } catch (Exception e) {
      String message = String.format("Failed to read from zookeeper: %s", e);
      LOG.error(message);
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500, ImmutableMap.of("message", message));

    }
    return Utils.buildResponse(HttpStatus.OK_200, clusterBeans);
  }

  /**
   * Initializes a given cluster. This may include adding designated tag
   * in DB and/or writing shard config to zookeeper.
   *
   * @param clusterName name of the cluster
   */
  @POST
  @Path("/initialize/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response initialize(@PathParam("clusterName") String clusterName) {
    // Create directly, we dont
    if (taskQueue.createCluster(clusterName)) {
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } else {
      String message = String.format("Cluster %s is already existed", clusterName);
      return Utils.buildResponse(HttpStatus.BAD_REQUEST_400, ImmutableMap.of("message", message));
    }
  }

  /**
   * Remove tag in DB and shard config in zookeeper.
   *
   * @param clusterName name of the cluster
   */
  @POST
  @Path("/remove/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response remove(@PathParam("clusterName") String clusterName) {
    if (taskQueue.removeCluster(clusterName)) {
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } else {
      String message = String.format("Cluster %s is already locked, cannot remove", clusterName);
      return Utils.buildResponse(HttpStatus.BAD_REQUEST_400, ImmutableMap.of("message", message));
    }
  }

  /**
   * Replaces a host in a given cluster with a new one. If new host is provided
   * in the query parameter, that host will be used to replace the old one.
   * Otherwise, controller will randomly pick one for the user.
   *
   *
   * @param clusterName name of the cluster
   * @param oldHost     host to be replaced, in the format of ip:port
   * @param newHostOp   (optional) new host to add, in the format of ip:port
   */
  @POST
  @Path("/replaceHost/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response replaceHost(@PathParam("clusterName") String clusterName,
                          @NotEmpty @QueryParam("oldHost") String oldHostString,
                          @QueryParam("newHost") Optional<String> newHostOp) {

    //TODO(angxu) support adding random new host later
    if (!newHostOp.isPresent()) {
      return Utils.buildResponse(HttpStatus.BAD_REQUEST_400, ImmutableMap.of("message", "New host is not specified"));
    }

    try {
      HostBean newHost = HostBean.fromUrlParam(newHostOp.get());
      HostBean oldHost = HostBean.fromUrlParam(oldHostString);
      TaskBase task = new RemoveHostTask(oldHost)
          .andThen(new PromoteTask())
          .andThen(
              new AddHostTask(
                  newHost.getIp(),
                  newHost.getPort(),
                  newHost.getAvailabilityZone(),
                  //TODO(angxu) make it configurable
                  "/rocksdb",
                  //TODO(angxu) make it configurable
                  50)
          )
          .andThen(new RebalanceTask())
          .andThen(new HealthCheckTask())
          //TODO(angxu) Add .retry(maxRetry) if necessary
          .getEntity();

      taskQueue.enqueueTask(task, clusterName, 0);
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } catch (JsonProcessingException e) {
      String message = "Cannot serialize parameters";
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500, ImmutableMap.of("message", message));
    }
  }

  /**
   * Loads sst files from s3 into a given cluster.
   *
   * @param clusterName name of the cluster
   * @param segmentName name of the segment
   * @param s3Bucket    S3 bucket name
   * @param s3Prefix    prefix of the S3 path
   * @param concurrency maximum number of hosts concurrently loading
   * @param rateLimit   s3 download size limit in mb
   */
  @POST
  @Path("/loadData/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response loadData(@PathParam("clusterName") String clusterName,
                       @NotEmpty @QueryParam("segmentName") String segmentName,
                       @NotEmpty @QueryParam("s3Bucket") String s3Bucket,
                       @NotEmpty @QueryParam("s3Prefix") String s3Prefix,
                       @QueryParam("concurrency") Optional<Integer> concurrency,
                       @QueryParam("rateLimit") Optional<Integer> rateLimit) {
    try {
      TaskBase task = new LoadSSTTask(segmentName, s3Bucket, s3Prefix,
          concurrency.orElse(20), rateLimit.orElse(64)).getEntity();
      taskQueue.enqueueTask(task, clusterName, 0);
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } catch (JsonProcessingException e) {
      String message = "Cannot serialize parameters";
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500, ImmutableMap.of("message", message));
    }
  }

  /**
   * Locks a given cluster. Outside system may use this API to synchronize
   * operations on the same cluster. It is caller's responsibility to properly
   * release the lock via {@link #unlock(String)}.
   *
   * @param clusterName name of the cluster to lock
   * @return true if the given cluster is locked, false otherwise
   */
  @POST
  @Path("/lock/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response lock(@PathParam("clusterName") String clusterName) {
    if (taskQueue.lockCluster(clusterName)) {
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } else {
      String message = String.format("Cluster %s is already locked, cannot double lock", clusterName);
      return Utils.buildResponse(HttpStatus.BAD_REQUEST_400, ImmutableMap.of("message", message));
    }
  }

  /**
   * Unlocks a given cluster.
   *
   * @param clusterName name of the cluster to unlock
   * @return true if the given cluster is unlocked, false otherwise
   */
  @POST
  @Path("/unlock/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response unlock(@PathParam("clusterName") String clusterName) {
    if (taskQueue.unlockCluster(clusterName)) {
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } else {
      String message = String.format("Cluster %s is not created yet", clusterName);
      return Utils.buildResponse(HttpStatus.BAD_REQUEST_400, ImmutableMap.of("message", message));
    }
  }

  /**
   * Send a LoggingTask to worker.
   * @param clusterName
   * @return
   * @throws Exception
   */
  @POST
  @Path("/logging/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendLogTask(@PathParam("clusterName") String clusterName,
                          @NotEmpty @QueryParam("message") String message) {
    try {
      TaskBase task = new LoggingTask(message).getEntity();
      taskQueue.enqueueTask(task, clusterName, 0);
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));                     
    } catch (JsonProcessingException e) {
      String errorMessage = "Cannot serialize parameters";
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500, ImmutableMap.of("message", errorMessage));
    }
  }


  /**
   * Send a healthcehck task to a cluster.
   * @param clusterName
   * @param intervalSeconds If not specified, it's a one-off task, otherwise the task is repeatable.
   * @param zkPrefix if not specified, use DEFAULT_ZK_PATH in WorkerConfig.
   */
  @POST
  @Path("/healthcheck/{clusterName : [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response healthcheck(@PathParam("clusterName") String clusterName,
                              @QueryParam("interval") Optional<Integer> intervalSeconds,
                              @QueryParam("zkPrefix") Optional<String> zkPrefix) {
    try {
      HealthCheckTask.Param param = new HealthCheckTask.Param();
      if (zkPrefix.isPresent()) {
        param.setZkPrefix(zkPrefix.get());
      }
      TaskBase healthCheckTask = new HealthCheckTask(param)
          .recur(intervalSeconds.orElse(0)).getEntity();
      taskQueue.enqueueTask(healthCheckTask, clusterName, 0);
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } catch (JsonProcessingException e) {
      String message = "Cannot serialize parameters";
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500,
          ImmutableMap.of("message", message));
    }
  }

  /**
   * Send a configcheck task to a cluster.
   * @param clusterName
   * @param intervalSeconds if not specified, it's a one-off task, otherwise the task is repeatable.
   * @param numReplicas the number of replicas per shard. If not speicfied, use default of 3.
   * @return
   */
  @POST
  @Path("/configcheck/{clusterName: [a-zA-Z0-9\\-_]+}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response configCheck(@PathParam("clusterName") String clusterName,
                              @QueryParam("interval") Optional<Integer> intervalSeconds,
                              @QueryParam("replicas") Optional<Integer> numReplicas) {
    try {
      ConfigCheckTask.Param param = new ConfigCheckTask.Param().setNumReplicas(numReplicas.orElse(3));
      TaskBase configCheckTask =
          new ConfigCheckTask(param).recur(intervalSeconds.orElse(0)).getEntity();
      taskQueue.enqueueTask(configCheckTask, clusterName, 0);
      return Utils.buildResponse(HttpStatus.OK_200, ImmutableMap.of("data", true));
    } catch (JsonProcessingException e) {
      String message = "Cannot serialize parameters: " + e.getMessage();
      return Utils.buildResponse(HttpStatus.INTERNAL_SERVER_ERROR_500,
          ImmutableMap.of("message", message));
    }
  }


  private ClusterBean checkExistenceAndGetClusterBean(String clusterName) throws Exception {
    if (zkClient.checkExists().forPath(zkPath + clusterName) == null) {
      LOG.error("Znode {} doesn't exist.", zkPath + clusterName);
      return null;
    }
    byte[] data = zkClient.getData().forPath(zkPath + clusterName);
    ClusterBean clusterBean = ConfigParser.parseClusterConfig(clusterName, data);
    if (clusterBean == null) {
      LOG.error("Failed to parse config for cluster {}.", clusterName);
    }
    return clusterBean;
  }

}
