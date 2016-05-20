package com.twitter.heron.scheduler.mesos;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.proto.scheduler.Scheduler;
import com.twitter.heron.proto.tmaster.TopologyMaster;
import com.twitter.heron.scheduler.mesos.framework.config.FrameworkConfiguration;
import com.twitter.heron.scheduler.mesos.framework.driver.MesosDriverFactory;
import com.twitter.heron.scheduler.mesos.framework.driver.MesosJobFramework;
import com.twitter.heron.scheduler.mesos.framework.driver.MesosTaskBuilder;
import com.twitter.heron.scheduler.mesos.framework.jobs.BaseJob;
import com.twitter.heron.scheduler.mesos.framework.jobs.JobScheduler;
import com.twitter.heron.scheduler.mesos.framework.state.PersistenceStore;
import com.twitter.heron.scheduler.mesos.framework.state.ZkPersistenceStore;
import com.twitter.heron.scheduler.mesos.util.NetworkUtility;
import com.twitter.heron.spi.common.*;
import com.twitter.heron.spi.scheduler.IScheduler;
import com.twitter.heron.spi.statemgr.SchedulerStateManagerAdaptor;
import com.twitter.heron.spi.utils.Runtime;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MesosScheduler implements IScheduler {
  private static final Logger LOG = Logger.getLogger(MesosScheduler.class.getName());

  private static final long MAX_WAIT_TIMEOUT_MS = 30 * 1000;

  private Config config;
  private Config runtime;
  private AtomicBoolean tmasterRestart;
  private Thread tmasterRunThread;
  private Process tmasterProcess;

  private TopologyAPI.Topology topology;
  private String topologyName;
  private SchedulerStateManagerAdaptor stateManager;

  private JobScheduler jobScheduler;
  private volatile CountDownLatch startLatch;

  private String executorCmdTemplate = "";

  private final Map<Integer, BaseJob> executorShardToJob = new ConcurrentHashMap<>();

  private PersistenceStore persistenceStore;

  private MesosJobFramework mesosJobFramework;

  private void startRegularContainers(PackingPlan packing) {
    LOG.info("We are to start the new mesos job scheduler now");
    this.topology = Runtime.topology(runtime);

    int shardId = 0;
    this.executorCmdTemplate = getExecutorCmdTemplate();

    for (PackingPlan.ContainerPlan container : packing.containers.values()) {
      shardId++;
      String executorCommand = String.format(executorCmdTemplate, shardId);
      executorShardToJob.put(shardId, getBaseJobTemplate(container, executorCommand));
    }

    LOG.info("Wait for jobScheduler's availability");
    startLatch = new CountDownLatch(1);
    if (mesosJobFramework.setSafeLatch(startLatch)) {
      try {
        if (!startLatch.await(MAX_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          throw new RuntimeException("Job Scheduler does not recover in expected time!");
        }
      } catch (InterruptedException e) {
        throw new RuntimeException("Mesos Scheduler is interrupted:", e);
      }
    }
    for (BaseJob job : executorShardToJob.values()) {
      jobScheduler.registerJob(job);
    }
    LOG.info("All containers job have been submitted to new mesos job scheduler");
    LOG.info("The BaseJob Info: ");
    LOG.info(executorShardToJob.toString());
  }

  // Parse config and construct the job scheduler
  private JobScheduler getJobScheduler() {
    String rootPath =
        String.format("%s/%s",
            MesosContext.frameworkZKRoot(config),
            topologyName);

    int connectionTimeoutMs =
        MesosContext.frameworkZKConnectTimeout(config);
    int sessionTimeoutMs =
        MesosContext.frameworkZKSessionTimeout(config);

    persistenceStore = new ZkPersistenceStore(
            MesosContext.frameworkZKEndpoint(config),
        connectionTimeoutMs, sessionTimeoutMs,
        rootPath);

    FrameworkConfiguration frameworkConfig = FrameworkConfiguration.getFrameworkConfiguration();
    frameworkConfig.schedulerName = topologyName + "-framework";
    frameworkConfig.master = MesosContext.mesosMasterURI(config);
    frameworkConfig.user = Context.role(config);

    frameworkConfig.failoverTimeoutSeconds = MesosContext.failoverTimeoutSeconds(config);

    frameworkConfig.reconciliationIntervalInMs = MesosContext.reconciliationIntervalMS(config);
    frameworkConfig.hostname = "";

    MesosTaskBuilder mesosTaskBuilder = new MesosTaskBuilder();

    mesosJobFramework = new MesosJobFramework(mesosTaskBuilder, persistenceStore, frameworkConfig);

    MesosDriverFactory mesosDriver = new MesosDriverFactory(mesosJobFramework, persistenceStore, frameworkConfig);
    JobScheduler jobScheduler = new JobScheduler(mesosJobFramework, persistenceStore, mesosDriver, frameworkConfig);

    return jobScheduler;
  }

  @Override
  public boolean onSchedule(PackingPlan packing) {
    // Start regular containers other than TMaster
    startRegularContainers(packing);
    return true;
  }

  private BaseJob getBaseJobTemplate(PackingPlan.ContainerPlan container, String command) {
    BaseJob jobDef = new BaseJob();

    jobDef.name = "container_" + container.id + "_" + UUID.randomUUID();
    jobDef.command = command;

    jobDef.retries = Integer.MAX_VALUE;
    jobDef.owner = Context.role(config);
    jobDef.runAsUser = Context.role(config);
    jobDef.description = "Container for id: " + container.id + " for topology: " + topologyName;
    jobDef.cpu = container.resource.cpu;
    jobDef.disk = container.resource.disk / Constants.MB;
    jobDef.mem = container.resource.ram / Constants.MB;
    jobDef.shell = true;

    jobDef.uris = new ArrayList<>();
    String topologyPath = Runtime.topologyPackageUri(runtime).toString();
    String heronCoreReleasePath = Context.corePackageUri(config);
    jobDef.uris.add(topologyPath);
    jobDef.uris.add(heronCoreReleasePath);


    return jobDef;
  }

  private static String extractFilenameFromUri(String url) {
    return url.substring(url.lastIndexOf('/') + 1, url.length());
  }

  private String getExecutorCmdTemplate() {
    String topologyTarfile = extractFilenameFromUri(
        Runtime.topologyPackageUri(runtime).toString());
    String heronCoreFile = extractFilenameFromUri(
        Context.corePackageUri(config));

    String cmd = String.format(
        "rm %s %s && mkdir log-files && ./heron-executor",
         topologyTarfile, heronCoreFile);

    StringBuilder command = new StringBuilder(cmd);

    String executorArgs = getHeronJobExecutorArguments(config);

    command.append(" %d");
    command.append(" " + executorArgs);

    return command.toString();
  }

  @Override
  public void initialize(Config config, Config runtime) {
    LOG.info("Initializing new mesos topology scheduler");
    this.tmasterRestart = new AtomicBoolean(true);
    this.config = config;
    this.runtime = runtime;

    // Start the jobScheduler
    this.jobScheduler = getJobScheduler();
    this.jobScheduler.start();

    // Start tmaster.
    createTmasterRunScript();
    tmasterRunThread = new Thread(new Runnable() {
      @Override
      public void run() {
        runTmaster();
      }
    });
    tmasterRunThread.setDaemon(true);
    tmasterRunThread.start();
  }

  @Override
  public void close() {
  }

  @Override
  public List<String> getJobLinks() {
    return null;
  }

  @Override
  public boolean onKill(Scheduler.KillTopologyRequest request) {
    for (BaseJob job : executorShardToJob.values()) {
      jobScheduler.deregisterJob(job.name, true);
    }

    CountDownLatch endLatch = new CountDownLatch(1);
    mesosJobFramework.setEndNotification(endLatch);

    LOG.info("Wait for the completeness of all mesos jobs");
    try {
      if (!endLatch.await(MAX_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Job Scheduler does not kill all jobs in expected time!");
      }
    } catch (InterruptedException e) {
      throw new RuntimeException("Mesos Scheduler is interrupted:", e);
    }

    // Clear the frameworkId
    LOG.info("Mesos jobs killed; to clean up...");
    return persistenceStore.removeFrameworkID() && persistenceStore.clean();
  }

  /*@Override
  public boolean onActivate(Scheduler.ActivateTopologyRequest request) {
    LOG.info("To activate topology: " + request.getTopologyName());

    try {
      communicateTMaster("activate");
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "We could not deactivate topology:", e);
      return false;
    }
    return true;
  }*/

  /*@Override
  public boolean onDeactivate(Scheduler.DeactivateTopologyRequest request) {
    LOG.info("To deactivate topology: " + request.getTopologyName());

    try {
      communicateTMaster("deactivate");
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "We could not deactivate topology:", e);
      return false;
    }
    return true;
  }*/

  @Override
  public boolean onRestart(Scheduler.RestartTopologyRequest request) {
    Integer shardId = request.getContainerIndex();
    BaseJob job = executorShardToJob.get(shardId);

    jobScheduler.restartJob(job.name);

    return true;
  }

  /**
   * Private methods
   */

  private void createTmasterRunScript() {
    // Create tmaster script.
    File tmasterRunScript = new File("run-tmaster.sh");
    if (tmasterRunScript.exists()) {
      tmasterRunScript.delete();
    }

    String heronTMasterArguments = getHeronTMasterArguments(config,
            MesosContext.tmastercontrollerPort(config),
            MesosContext.tmasterMainPort(config),
            MesosContext.tmasterStatPort(config),
            MesosContext.tmasterShellPort(config),
            MesosContext.tmasterMetricsMgrPort(config));

    try {
      FileWriter fw = new FileWriter(tmasterRunScript);
      fw.write("#!/bin/bash");
      fw.write(System.lineSeparator());
      fw.write("./heron-executor 0 " + heronTMasterArguments);
      fw.write(System.lineSeparator());
      fw.close();
      tmasterRunScript.setExecutable(true);
    } catch (IOException ioe) {
      LOG.log(Level.SEVERE, "Failed to create run-tmaster script", ioe);
    }
  }

  private void runTmaster() {
    while (tmasterRestart.get()) {
      LOG.info("Starting tmaster executor");

      try {
        tmasterProcess = ShellUtils.runASyncProcess(
            true, "./run-tmaster.sh", new File("."));
        int exitValue = tmasterProcess.waitFor();  // Block.
        LOG.log(Level.SEVERE, "Tmaster process exitted. Exit: " + exitValue);
      } catch (InterruptedException e) {
        try {
          int exitValue = tmasterProcess.exitValue();
          LOG.log(Level.SEVERE, "Tmaster process exitted. Exit: " + exitValue);
          continue;  // Try restarting.
        } catch (IllegalThreadStateException ex) {
          LOG.log(Level.SEVERE, "Thread interrupted", ex);
        }
      }
    }
  }

  /**
   * Communicate with TMaster with command
   *
   * @param command the command requested to TMaster, activate or deactivate.
   * @return true if the requested command is processed successfully by tmaster
   */
  protected boolean communicateTMaster(String command) {
    // Get the TMasterLocation
    TopologyMaster.TMasterLocation location =
        NetworkUtility.awaitResult(stateManager.getTMasterLocation(),
            5,
            TimeUnit.SECONDS);

    String endpoint = String.format("http://%s:%d/%s?topologyid=%s",
        location.getHost(),
        location.getControllerPort(),
        command,
        location.getTopologyId());

    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(endpoint).openConnection();
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to get connection to tmaster: ", e);
      return false;
    }

    NetworkUtility.sendHttpGetRequest(connection);

    try {
      if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
        LOG.info("Successfully  " + command);
        return true;
      }
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to " + command + " :", e);
    } finally {
      connection.disconnect();
    }

    return true;
  }

  public static String getHeronJobExecutorArguments(Config config) {
    return getHeronExecutorArguments(config,
        "{{task.ports[STMGR_PORT]}}", "{{task.ports[METRICMGR_PORT]}}",
        "{{task.ports[BACK_UP]}}", "{{task.ports[SHELL]}}",
        "{{task.ports[BACK_UP_1]}}");
  }

  public static String getHeronExecutorArguments(
      Config config,
      String sPort1,
      String sPort2,
      String sPort3,
      String sPort4,
      String sPort5) {

    return String.format(
        "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\" " +
            "\"%s\" \"%s\" \"%s\"",
        config.getStringValue("TOPOLOGY_NAME"), config.getStringValue("TOPOLOGY_ID"), config.getStringValue("TOPOLOGY_DEFN"),
            config.getStringValue("INSTANCE_DISTRIBUTION"), config.getStringValue("ZK_NODE"), config.getStringValue("ZK_ROOT"),
            config.getStringValue("TMASTER_BINARY"), config.getStringValue("STMGR_BINARY"), config.getStringValue("METRICS_MGR_CLASSPATH"),
            config.getStringValue("INSTANCE_JVM_OPTS_IN_BASE64"), config.getStringValue("CLASSPATH"), sPort1,
        sPort2, sPort3, config.getStringValue("HERON_INTERNALS_CONFIG_FILENAME"),
            config.getStringValue("COMPONENT_RAMMAP"), config.getStringValue("COMPONENT_JVM_OPTS_IN_BASE64"), config.getStringValue("PKG_TYPE"),
            config.getStringValue("TOPOLOGY_JAR_FILE"), config.getStringValue("HERON_JAVA_HOME"),
        sPort4, config.getStringValue("LOG_DIR"), config.getStringValue("HERON_SHELL_BINARY"),
        sPort5);
  }

  public static String getHeronTMasterArguments(
      Config config,
      int port1,  // Port for TMaster Controller and Stream-manager port
      int port2,  // Port for TMaster and Stream-manager communication amd Stream manager with
      // metric manager communicaiton.
      int port3,   // Port for TMaster stats export. Used by tracker.
      int shellPort,  // Port for heorn-shell
      int metricsmgrPort      // Port for MetricsMgr in TMasterContainer
  ) {
    return getHeronExecutorArguments(config, "" + port1, "" + port2,
        "" + port3, "" + shellPort, "" + metricsmgrPort);
  }
}
