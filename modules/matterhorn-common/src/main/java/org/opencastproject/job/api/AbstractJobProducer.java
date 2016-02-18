/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.job.api;

import static com.entwinemedia.fn.data.Opt.none;
import static com.entwinemedia.fn.data.Opt.some;

import com.entwinemedia.fn.data.Opt;

import org.opencastproject.job.api.Incident.Severity;
import org.opencastproject.job.api.Job.Status;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.User;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.serviceregistry.api.Incidents;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.serviceregistry.api.ServiceRegistryException;
import org.opencastproject.serviceregistry.api.SystemLoad;
import org.opencastproject.serviceregistry.api.SystemLoad.NodeLoad;
import org.opencastproject.serviceregistry.api.UndispatchableJobException;
import org.opencastproject.util.JobCanceledException;
import org.opencastproject.util.NotFoundException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class serves as a convenience for services that implement the {@link JobProducer} api to deal with handling long
 * running, asynchronous operations.
 */
public abstract class AbstractJobProducer implements JobProducer {

  /** The logger */
  static final Logger logger = LoggerFactory.getLogger(AbstractJobProducer.class);

  /** The types of job that this producer can handle */
  protected String jobType = null;

  /** To enable threading when dispatching jobs */
  protected ExecutorService executor = Executors.newCachedThreadPool();

  /**
   * Creates a new abstract job producer for jobs of the given type.
   *
   * @param jobType
   *         the job type
   */
  public AbstractJobProducer(String jobType) {
    this.jobType = jobType;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.job.api.JobProducer#getJobType()
   */
  @Override
  public String getJobType() {
    return jobType;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.job.api.JobProducer#countJobs(org.opencastproject.job.api.Job.Status)
   */
  @Override
  public long countJobs(Status status) throws ServiceRegistryException {
    if (status == null)
      throw new IllegalArgumentException("Status must not be null");
    return getServiceRegistry().count(getJobType(), status);
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.job.api.JobProducer#acceptJob(org.opencastproject.job.api.Job)
   */
  @Override
  public void acceptJob(final Job job) throws ServiceRegistryException {
    final Job runningJob;
    try {
      job.setStatus(Job.Status.RUNNING);
      runningJob = getServiceRegistry().updateJob(job);
    } catch (NotFoundException e) {
      throw new IllegalStateException(e);
    }
    executor.submit(new JobRunner(runningJob, getServiceRegistry().getCurrentJob()));
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.job.api.JobProducer#isReadyToAcceptJobs(String)
   */
  @Override
  public boolean isReadyToAcceptJobs(String operation) throws ServiceRegistryException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.job.api.JobProducer#isReadyToAccept(org.opencastproject.job.api.Job)
   */
  @Override
  public boolean isReadyToAccept(Job job) throws ServiceRegistryException, UndispatchableJobException {
    NodeLoad maxload;
    try {
      maxload = getServiceRegistry().getMaxLoadOnNode(getServiceRegistry().getRegistryHostname());
    } catch (NotFoundException e) {
      throw new ServiceRegistryException(e);
    }

    SystemLoad systemLoad = getServiceRegistry().getCurrentHostLoads(true);
    //Note: We are not adding the job load in the next line because it is already accounted for in the load values we
    //get back from the service registry.
    float currentLoad = systemLoad.get(getServiceRegistry().getRegistryHostname()).getLoadFactor();
    if (currentLoad > maxload.getLoadFactor()) {
      logger.debug("Declining job {} of type {} because load of {} would exceed this node's limit of {}.",
              new Object[] { job.getId(), job.getJobType(), currentLoad, maxload.getLoadFactor() });
      return false;
    }
    logger.debug("Accepting job {} of type {} because load of {} is within this node's limit of {}.",
            new Object[] { job.getId(), job.getJobType(), currentLoad, maxload.getLoadFactor() });
    return true;
  }

  /** Shorthand for {@link #getServiceRegistry()}.incident() */
  public Incidents incident() {
    return getServiceRegistry().incident();
  }

  /**
   * Returns a reference to the service registry.
   *
   * @return the service registry
   */
  protected abstract ServiceRegistry getServiceRegistry();

  /**
   * Returns a reference to the security service
   *
   * @return the security service
   */
  protected abstract SecurityService getSecurityService();

  /**
   * Returns a reference to the user directory service
   *
   * @return the user directory service
   */
  protected abstract UserDirectoryService getUserDirectoryService();

  /**
   * Returns a reference to the organization directory service.
   *
   * @return the organization directory service
   */
  protected abstract OrganizationDirectoryService getOrganizationDirectoryService();

  /**
   * Asks the overriding class to process the arguments using the given operation. The result will be added to the
   * associated job as the payload.
   *
   * @param job
   *         the job to process
   * @return the operation result
   * @throws Exception
   */
  protected abstract String process(Job job) throws Exception;

  /** A utility class to run jobs */
  class JobRunner implements Callable<Void> {

    /** The job to dispatch */
    private final long jobId;

    /** The current job */
    private final Opt<Long> currentJobId;

    /**
     * Constructs a new job runner
     *
     * @param job
     *         the job to run
     * @param currentJob
     *         the current running job
     */
    JobRunner(Job job, Job currentJob) {
      this.jobId = job.getId();
      if (currentJob != null) {
        this.currentJobId = some(currentJob.getId());
      }
      else {
        currentJobId = none();
      }
    }

    @Override
    public Void call() throws Exception {
      final SecurityService securityService = getSecurityService();
      final ServiceRegistry serviceRegistry = getServiceRegistry();
      final Job jobBeforeProcessing = serviceRegistry.getJob(jobId);

      if (currentJobId.isSome())
        serviceRegistry.setCurrentJob(serviceRegistry.getJob(currentJobId.get()));

      final Organization organization = getOrganizationDirectoryService().getOrganization(jobBeforeProcessing.getOrganization());
      securityService.setOrganization(organization);
      final User user = getUserDirectoryService().loadUser(jobBeforeProcessing.getCreator());
      securityService.setUser(user);

      try {
        final String payload = process(jobBeforeProcessing);
        handleSuccessfulProcessing(payload);
      } catch (Throwable t) {
        handleFailedProcessing(t);
      } finally {
        serviceRegistry.setCurrentJob(null);
        securityService.setUser(null);
        securityService.setOrganization(null);
      }

      return null;
    }

    private void handleSuccessfulProcessing(final String payload) throws Exception {
      // The job may gets updated internally during processing. It therefore needs to be reload from the service
      // registry in order to prevent inconsistencies.
      final Job jobAfterProcessing = getServiceRegistry().getJob(jobId);
      jobAfterProcessing.setPayload(payload);
      jobAfterProcessing.setStatus(Status.FINISHED);
      getServiceRegistry().updateJob(jobAfterProcessing);
    }

    private void handleFailedProcessing(final Throwable t) throws Exception {
      if (t instanceof JobCanceledException) {
        logger.info(t.getMessage());
      } else {
        final Job jobAfterProcessing = getServiceRegistry().getJob(jobId);
        jobAfterProcessing.setStatus(Status.FAILED);
        getServiceRegistry().updateJob(jobAfterProcessing);
        getServiceRegistry().incident().unhandledException(jobAfterProcessing, Severity.FAILURE, t);
        logger.error("Error handling operation '{}': {}", jobAfterProcessing.getOperation(), ExceptionUtils.getStackTrace(t));
        if (t instanceof ServiceRegistryException)
          throw (ServiceRegistryException) t;
      }
    }

  }
}
