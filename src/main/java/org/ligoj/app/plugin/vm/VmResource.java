package org.ligoj.app.plugin.vm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;

import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.time.FastDateFormat;
import org.ligoj.app.api.ConfigurablePlugin;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.plugin.AbstractServicePlugin;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The Virtual Machine service.
 */
@Service
@Path(VmResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmResource extends AbstractServicePlugin implements InitializingBean, ConfigurablePlugin {

	/**
	 * Group name used for all triggers.
	 */
	protected static final String SCHEDULE_TRIGGER_GROUP = "vm-schedule";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = BASE_URL + "/vm";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_KEY = SERVICE_URL.replace('/', ':').substring(1);

	@Autowired
	private JobDetailFactoryBean vmJobDetailFactoryBean;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected ServicePluginLocator servicePluginLocator;

	@Autowired
	protected SecurityHelper securityHelper;

	@Autowired
	private VmExecutionRepository vmExecutionRepository;

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	@Override
	@Transactional
	public void delete(final int subscription, final boolean deleteRemoteData) throws SchedulerException {
		unscheduleAll(subscription);

		// Also remove execution history
		vmExecutionRepository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Remove all schedules of this subscription from the current scheduler,
	 * then from the data base.
	 */
	protected void unscheduleAll(final int subscription) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> subscription == VmJob.getSubscription(triggerKey));

		// Remove all schedules associated to this subscription
		vmScheduleRepository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Remove a schedule of this subscription for a specific operation from the
	 * current scheduler, then from the data base.
	 */
	private void unschedule(final int schedule) throws SchedulerException {
		unscheduleQuartz(schedule);

		// Remove all schedules associated to this subscription from persisted
		// entities
		vmScheduleRepository.delete(schedule);
	}

	private void unscheduleQuartz(final int schedule) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> schedule == VmJob.getSchedule(triggerKey));
	}

	/**
	 * Remove all schedules matching the given predicate from the current
	 * scheduler, then from the data base.
	 */
	private void unscheduleAll(final Predicate<TriggerKey> predicate) throws SchedulerException {
		// Remove current schedules from the memory
		final Scheduler scheduler = vmSchedulerFactoryBean.getObject();
		for (final TriggerKey triggerKey : scheduler.getTriggerKeys(GroupMatcher.groupEquals(SCHEDULE_TRIGGER_GROUP))) {
			if (predicate.test(triggerKey)) {
				// Match subscription and operation, unschedule this trigger
				scheduler.unscheduleJob(triggerKey);
			}
		}
	}

	/**
	 * Persist the trigger in the Quartz scheduler.
	 */
	private VmSchedule persistTrigger(final VmSchedule schedule) throws SchedulerException {
		// The trigger for the common VM Job will the following convention :
		// schedule.id-subscription.id
		final String id = VmJob.format(schedule);
		final JobDetailImpl object = (JobDetailImpl) vmJobDetailFactoryBean.getObject();
		object.getJobDataMap().put("vmServicePlugin", this);
		final Trigger trigger = TriggerBuilder.newTrigger().withIdentity(id, SCHEDULE_TRIGGER_GROUP)
				.withSchedule(CronScheduleBuilder.cronSchedule(schedule.getCron())).forJob(object)
				.usingJobData("subscription", schedule.getSubscription().getId()).usingJobData("operation", schedule.getOperation().name())
				.usingJobData("schedule", schedule.getId()).build();

		// Add this trigger
		vmSchedulerFactoryBean.getObject().scheduleJob(trigger);
		return schedule;
	}

	@Override
	@Transactional
	public void afterPropertiesSet() throws SchedulerException {
		// Recreate all schedules from the database
		final List<VmSchedule> schedules = vmScheduleRepository.findAll();
		log.info("Schedules {} jobs from database", schedules.size());
		for (final VmSchedule schedule : schedules) {
			persistTrigger(schedule);
		}

	}

	/**
	 * Execute a {@link VmOperation} to the associated VM and checks its
	 * visibility against the current principal user. This a synchronous call,
	 * but the effective execution is delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} identifier associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 */
	@POST
	@Path("{subscription:\\d+}/{operation}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public void execute(@PathParam("subscription") final int subscription, @PathParam("operation") final VmOperation operation) {
		execute(subscriptionResource.checkVisibleSubscription(subscription), operation);
	}

	/**
	 * Execute a {@link VmOperation} to the associated VM. This a synchronous
	 * call, but the effective execution is delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 */
	@Transactional
	public void execute(final Subscription subscription, final VmOperation operation) {
		final String node = subscription.getNode().getId();
		final String trigger = securityHelper.getLogin();
		log.info("Operation {} on subscription {}, node {} is requested by {}", operation, subscription.getId(), node, trigger);
		final VmExecution vmExecution = new VmExecution();
		vmExecution.setOperation(operation);
		vmExecution.setSubscription(subscription);
		vmExecution.setTrigger(trigger);
		vmExecution.setDate(new Date());

		try {
			// Execute the operation if plug-in still available
			servicePluginLocator.getResourceExpected(node, VmServicePlugin.class).execute(subscription.getId(), operation);
			log.info("Operation {} on subscription {}, node {} : succeed", operation, subscription.getId(), node);
			vmExecution.setSucceed(true);
		} catch (final Exception e) {
			// Something goes wrong for this VM, this log would be considered
			// for reporting
			vmExecution.setError(e.getMessage());
			log.error("Operation {} on subscription {}, node {} : failed", operation, subscription.getId(), e);
		} finally {
			// Persist the execution result
			vmExecutionRepository.saveAndFlush(vmExecution);
		}
	}

	@GET
	@Path("{subscription:\\d+}")
	@Override
	@Transactional
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public VmConfigurationVo getConfiguration(@PathParam("subscription") final int subscription) {
		// Check the subscription is visible
		subscriptionResource.checkVisibleSubscription(subscription);

		// Get the details
		final VmConfigurationVo result = new VmConfigurationVo();
		final List<VmScheduleVo> scedules = new ArrayList<>();
		for (final VmSchedule schedule : vmScheduleRepository.findBySubscription(subscription)) {
			// Copy basic attributes
			final VmScheduleVo vo = new VmScheduleVo();
			vo.setCron(schedule.getCron());
			vo.setOperation(schedule.getOperation());
			vo.setId(schedule.getId());
			scedules.add(vo);
		}
		result.setSchedules(scedules);
		return result;
	}

	/**
	 * Return the full execution report for the related VM. No time limit.
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @param file
	 *            The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{subscription:\\d+}/{file:.*.csv}")
	public Response downloadReport(@PathParam("subscription") final int subscription, @PathParam("file") final String file) {
		final Subscription entity = subscriptionResource.checkVisibleSubscription(subscription);
		return AbstractToolPluginResource.download(o -> writeReport(entity, o), file).build();
	}

	/**
	 * Write all execution related to given subscription, from the oldest to the
	 * newest.
	 */
	private void writeReport(final Subscription subscription, final OutputStream output) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final FastDateFormat df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		writer.write("dateHMS;timestamp;operation;subscription;project;projectKey;projectName;node;trigger;succeed");
		for (final VmExecution execution : vmExecutionRepository.findAllBy("subscription.id", subscription.getId())) {
			writer.write('\n');
			writer.write(df.format(execution.getDate()));
			writer.write(';');
			writer.write(String.valueOf(execution.getDate().getTime()));
			writer.write(';');
			writer.write(execution.getOperation().name());
			writer.write(';');
			writer.write(String.valueOf(subscription.getId()));
			writer.write(';');
			writer.write(String.valueOf(subscription.getProject().getId()));
			writer.write(';');
			writer.write(subscription.getProject().getPkey());
			writer.write(';');
			writer.write(subscription.getProject().getName().replaceAll("\"", "'"));
			writer.write(';');
			writer.write(subscription.getNode().getId());
			writer.write(';');
			writer.write(execution.getTrigger());
			writer.write(';');
			writer.write(String.valueOf(execution.isSucceed()));
		}

		// Ensure buffer is flushed
		writer.flush();
	}

	/**
	 * Create a new schedule.
	 * 
	 * @param subscription
	 *            The subscription identifier to update.
	 * @param schedule
	 *            The schedule to save or update. The CRON expression may be
	 *            either in the 5 either in 6 parts. The optional 6th
	 *            corresponds to the "seconds" and will be prepended to the
	 *            expression to conform to Quartz format.
	 */
	@POST
	@Transactional
	public int createSchedule(final VmScheduleVo schedule) throws SchedulerException {
		return persistTrigger(checkAndSaveSchedule(schedule, new VmSchedule())).getId();
	}

	/**
	 * Update an existing schedule.
	 * 
	 * @param subscription
	 *            The subscription identifier to update.
	 * @param schedule
	 *            The schedule to save or update. The CRON expression may be
	 *            either in the 5 either in 6 parts. The optional 6th
	 *            corresponds to the "seconds" and will be prepended to the
	 *            expression to conform to Quartz format.
	 */
	@PUT
	@Transactional
	public void updateSchedule(final VmScheduleVo schedule) throws SchedulerException {
		// Persist the new schedules for each provided CRON
		VmSchedule entity = checkAndSaveSchedule(schedule, vmScheduleRepository.findOneExpected(schedule.getId()));

		// Remove current schedules from the Quartz memory
		unscheduleQuartz(schedule.getId());

		persistTrigger(checkAndSaveSchedule(schedule, entity));
	}

	private VmSchedule checkAndSaveSchedule(final VmScheduleVo schedule, final VmSchedule entity) {
		// Check the subscription is visible
		final Subscription subscription = subscriptionResource.checkVisibleSubscription(schedule.getSubscription());

		if (schedule.getCron().split(" ").length == 6) {
			// Add the missing "seconds" part
			schedule.setCron(schedule.getCron() + " *");
		}
		// Check expressions first
		if (!CronExpression.isValidExpression(schedule.getCron())) {
			throw new ValidationJsonException("cron", "vm-cron");
		}

		// Every second is not accepted
		if (schedule.getCron().startsWith("* ")) {
			throw new ValidationJsonException("cron", "vm-cron-second");
		}

		entity.setSubscription(subscription);
		entity.setOperation(schedule.getOperation());
		entity.setCron(schedule.getCron());

		// Persist the new schedules for each provided CRON
		vmScheduleRepository.saveAndFlush(entity);
		return entity;
	}

	/**
	 * Delete the schedule entity. Related subscription's visibility is checked.
	 * 
	 * @param schedule
	 *            The schedule to delete.
	 */
	@DELETE
	@Path("{id:\\d+}")
	@Transactional
	public void deleteSchedule(@PathParam("id") final int schedule) throws SchedulerException {
		// Check the subscription is visible
		final VmSchedule entity = vmScheduleRepository.findOneExpected(schedule);
		subscriptionResource.checkVisibleSubscription(entity.getSubscription().getId());

		// Clear the specific schedule
		unschedule(schedule);
	}
}
