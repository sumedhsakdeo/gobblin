package gobblin.runtime.job_catalog;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractIdleService;
import com.typesafe.config.Config;

import gobblin.configuration.ConfigurationKeys;
import gobblin.instrumented.Instrumented;
import gobblin.metrics.GobblinMetrics;
import gobblin.metrics.MetricContext;
import gobblin.metrics.Tag;
import gobblin.runtime.api.GobblinInstanceEnvironment;
import gobblin.runtime.api.JobCatalog;
import gobblin.runtime.api.JobCatalogListener;
import gobblin.runtime.api.JobSpec;
import gobblin.runtime.api.JobSpecNotFoundException;
import gobblin.runtime.util.FSJobCatalogHelper;
import gobblin.util.PathUtils;
import gobblin.util.PullFileLoader;
import gobblin.util.filesystem.PathAlterationDetector;
import gobblin.util.filesystem.PathAlterationObserver;

import lombok.Getter;


public class ImmutableFSJobCatalog extends AbstractIdleService implements JobCatalog {

  protected final FileSystem fs;
  protected final Config sysConfig;
  protected final MetricContext metricContext;
  protected final StandardMetrics metrics;

  protected final JobCatalogListenersList listeners;
  protected static final Logger LOGGER = LoggerFactory.getLogger(ImmutableFSJobCatalog.class);

  protected final PullFileLoader loader;

  /* The root configuration directory path.*/
  protected final Path jobConfDirPath;
  private final FSJobCatalogHelper.JobSpecConverter converter;

  // A monitor for changes to job conf files for general FS
  // This embedded monitor is monitoring job configuration files instead of JobSpec Object.
  protected final PathAlterationDetector pathAlterationDetector;

  public ImmutableFSJobCatalog(Config sysConfig)
      throws IOException {
    this(sysConfig, null);
  }

  public ImmutableFSJobCatalog(GobblinInstanceEnvironment env)
      throws IOException {
    this(env.getSysConfig().getConfig(), null, Optional.of(env.getMetricContext()),
         env.isInstrumentationEnabled());
  }

  public ImmutableFSJobCatalog(GobblinInstanceEnvironment env, PathAlterationObserver observer)
      throws IOException {
    this(env.getSysConfig().getConfig(), observer, Optional.of(env.getMetricContext()),
         env.isInstrumentationEnabled());
  }

  public ImmutableFSJobCatalog(Config sysConfig, PathAlterationObserver observer)
      throws IOException {
    this(sysConfig, observer, Optional.<MetricContext>absent(), GobblinMetrics.isEnabled(sysConfig));
  }

  public ImmutableFSJobCatalog(Config sysConfig, PathAlterationObserver observer, Optional<MetricContext> parentMetricContext,
      boolean instrumentationEnabled)
      throws IOException {
    this.sysConfig = sysConfig;
    ConfigAccessor cfgAccessor = new ConfigAccessor(this.sysConfig);

    this.jobConfDirPath = new Path(cfgAccessor.getJobConfDir());
    this.fs = this.jobConfDirPath.getFileSystem(new Configuration());
    this.listeners = new JobCatalogListenersList(Optional.of(LOGGER));

    this.loader = new PullFileLoader(jobConfDirPath, jobConfDirPath.getFileSystem(new Configuration()),
        cfgAccessor.getJobConfigurationFileExtensions(),
        PullFileLoader.DEFAULT_HOCON_PULL_FILE_EXTENSIONS);
    this.converter = new FSJobCatalogHelper.JobSpecConverter(this.jobConfDirPath, getInjectedExtension());

    long pollingInterval = cfgAccessor.getPollingInterval();
    this.pathAlterationDetector = new PathAlterationDetector(pollingInterval);

    // If absent, the Optional object will be created automatically by addPathAlterationObserver
    Optional<PathAlterationObserver> observerOptional = Optional.fromNullable(observer);
    FSPathAlterationListenerAdaptor configFilelistener =
        new FSPathAlterationListenerAdaptor(this.jobConfDirPath, this.loader, this.sysConfig, this.listeners,
            this.converter);
    FSJobCatalogHelper.addPathAlterationObserver(this.pathAlterationDetector, configFilelistener, observerOptional,
        this.jobConfDirPath);

    if (instrumentationEnabled) {
      MetricContext realParentCtx =
          parentMetricContext.or(Instrumented.getMetricContext(new gobblin.configuration.State(), getClass()));
      this.metricContext = realParentCtx.childBuilder("JobCatalog").build();
      this.metrics = new StandardMetrics(this);
    }
    else {
      this.metricContext = null;
      this.metrics = null;
    }
  }

  @Override
  protected void startUp()
      throws IOException {
    this.pathAlterationDetector.start();
  }

  @Override
  protected void shutDown()
      throws IOException, InterruptedException {
    this.pathAlterationDetector.stop();
  }

  /**
   * Fetch all the job files under the jobConfDirPath
   * @return A collection of JobSpec
   */
  @Override
  public synchronized List<JobSpec> getJobs() {
    return Lists.transform(Lists.newArrayList(
        loader.loadPullFilesRecursively(loader.getRootDirectory(), this.sysConfig, shouldLoadGlobalConf())),
        this.converter);
  }

  /**
   * Fetch single job file based on its URI,
   * return null requested URI not existed
   * @param uri The relative Path to the target job configuration.
   * @return
   */
  @Override
  public synchronized JobSpec getJobSpec(URI uri)
      throws JobSpecNotFoundException {
    try {
      Path targetJobSpecFullPath = getPathForURI(this.jobConfDirPath, uri);
      return this.converter.apply(loader.loadPullFile(targetJobSpecFullPath, this.sysConfig, shouldLoadGlobalConf()));
    } catch (IOException e) {
      throw new RuntimeException("IO exception thrown on loading single job configuration file:" + e.getMessage());
    }
  }

  /**
   * For each new coming JobCatalogListener, react accordingly to add all existing JobSpec.
   * @param jobListener
   */
  @Override
  public synchronized void addListener(JobCatalogListener jobListener) {
    Preconditions.checkNotNull(jobListener);

    this.listeners.addListener(jobListener);

    List<JobSpec> currentJobSpecList = this.getJobs();
    if (currentJobSpecList == null || currentJobSpecList.size() == 0) {
      return;
    } else {
      for (JobSpec jobSpecEntry : currentJobSpecList) {
        JobCatalogListener.AddJobCallback addJobCallback = new JobCatalogListener.AddJobCallback(jobSpecEntry);
        this.listeners.callbackOneListener(addJobCallback, jobListener);
      }
    }
  }

  @Override
  public void registerWeakJobCatalogListener(JobCatalogListener jobListener) {
    this.listeners.registerWeakJobCatalogListener(jobListener);

    List<JobSpec> currentJobSpecList = this.getJobs();
    if (currentJobSpecList == null || currentJobSpecList.size() == 0) {
      return;
    } else {
      for (JobSpec jobSpecEntry : currentJobSpecList) {
        JobCatalogListener.AddJobCallback addJobCallback = new JobCatalogListener.AddJobCallback(jobSpecEntry);
        this.listeners.callbackOneListener(addJobCallback, jobListener);
      }
    }
  }

  @Override
  public synchronized void removeListener(JobCatalogListener jobListener) {
    this.listeners.removeListener(jobListener);
  }

  /**
   * For immutable job catalog,
   * @return
   */
  public boolean shouldLoadGlobalConf() {
    return true;
  }

  /**
   *
   * @param jobConfDirPath The directory path for job cofniguration files.
   * @param uri Uri as the identifier of JobSpec
   * @return
   */
  protected Path getPathForURI(Path jobConfDirPath, URI uri) {
    return PathUtils.mergePaths(jobConfDirPath, new Path(uri));
  }

  protected Optional<String> getInjectedExtension() {
    return Optional.absent();
  }

  @Override public MetricContext getMetricContext() {
    return this.metricContext;
  }

  @Override public boolean isInstrumentationEnabled() {
    return null != this.metricContext;
  }

  @Override public List<Tag<?>> generateTags(gobblin.configuration.State state) {
    return Collections.emptyList();
  }

  @Override public void switchMetricContext(List<Tag<?>> tags) {
    throw new UnsupportedOperationException();
  }

  @Override public void switchMetricContext(MetricContext context) {
    throw new UnsupportedOperationException();
  }

  @Override public StandardMetrics getMetrics() {
    return this.metrics;
  }

  @Getter
  public static class ConfigAccessor {
    private final Config cfg;
    private final long pollingInterval;
    private final String jobConfDir;
    private final Set<String> JobConfigurationFileExtensions;

    public ConfigAccessor(Config cfg) {
      this.cfg = cfg;
      Preconditions.checkArgument(this.cfg.hasPath(ConfigurationKeys.JOB_CONFIG_FILE_GENERAL_PATH_KEY));
      this.pollingInterval = this.cfg.hasPath(ConfigurationKeys.JOB_CONFIG_FILE_MONITOR_POLLING_INTERVAL_KEY) ?
          this.cfg.getLong(ConfigurationKeys.JOB_CONFIG_FILE_MONITOR_POLLING_INTERVAL_KEY)  :
          ConfigurationKeys.DEFAULT_JOB_CONFIG_FILE_MONITOR_POLLING_INTERVAL;
      this.jobConfDir = this.cfg.getString(ConfigurationKeys.JOB_CONFIG_FILE_GENERAL_PATH_KEY);
      this.JobConfigurationFileExtensions = ImmutableSet.<String>copyOf(Splitter.on(",")
          .omitEmptyStrings()
          .trimResults()
          .split(getJobConfigurationFileExtensionsString()));
    }

    private String getJobConfigurationFileExtensionsString() {
      String propValue = this.cfg.hasPath(ConfigurationKeys.JOB_CONFIG_FILE_EXTENSIONS_KEY) ?
          this.cfg.getString(ConfigurationKeys.JOB_CONFIG_FILE_EXTENSIONS_KEY).toLowerCase() :
            ConfigurationKeys.DEFAULT_JOB_CONFIG_FILE_EXTENSIONS;
      return propValue;
    }
  }
}
