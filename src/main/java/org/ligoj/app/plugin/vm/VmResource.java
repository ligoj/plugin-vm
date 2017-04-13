package org.ligoj.app.plugin.vm;

import java.util.ArrayList;
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

import org.ligoj.app.api.ConfigurablePlugin;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.plugin.AbstractServicePlugin;
import org.ligoj.app.resource.subscription.SubscriptionResource;
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

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	@Override
	@Transactional
	public void delete(final int subscription, final boolean deleteRemoteData) throws SchedulerException {
		unscheduleAll(subscription);
	}

	/**
	 * Remove all schedules of this subscription from the current scheduler,
	 * then from the data base.
	 */
	protected void unscheduleAll(final int subscription) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> subscription == VmJob.getSubscription(triggerKey));

		// Remove all schedules associated to this subscription from persisted
		// entities
		vmScheduleRepository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Remove a schedule of this subscription for a specific operation from the
	 * current scheduler, then from the data base.
	 */
	private void unschedule(final int subscription, final VmOperation operation) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> subscription == VmJob.getSubscription(triggerKey)
				&& operation == VmJob.getOperation(triggerKey));

		// Remove all schedules associated to this subscription from persisted
		// entities
		vmScheduleRepository.deleteBySubscriptionAndOperation(subscription, operation);
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
	 * Schedule a VM operation : persisted and scheduled in memory
	 */
	private void schedule(final Subscription subscription, final String cron, final VmOperation operation)
			throws SchedulerException {
		// CRON expression has been provided, schedule it

		// First in the data base : reflect the "delete" cleaning order
		persistSchedule(subscription, cron, operation);

		// Update quartz scheduler
		persistTrigger(subscription, cron, operation);
	}

	/**
	 * Persist the trigger in the scheduler.
	 */
	private void persistTrigger(final Subscription subscription, final String cron, final VmOperation operation)
			throws SchedulerException {
		// The trigger for the common VM Job will the following convention :
		// subscriptionId-operationName
		final String id = VmJob.format(subscription.getId(), operation);
		final JobDetailImpl object = (JobDetailImpl) vmJobDetailFactoryBean.getObject();
		object.getJobDataMap().put("vmServicePlugin", this);
		final Trigger trigger = TriggerBuilder.newTrigger().withIdentity(id, SCHEDULE_TRIGGER_GROUP)
				.withSchedule(CronScheduleBuilder.cronSchedule(cron)).forJob(object)
				.usingJobData("subscription", subscription.getId()).usingJobData("node", subscription.getNode().getId())
				.usingJobData("operation", operation.name()).build();

		// Add this trigger
		vmSchedulerFactoryBean.getObject().scheduleJob(trigger);
	}

	/**
	 * Persist a schedule in data base.
	 */
	protected void persistSchedule(final Subscription subscription, final String cron, final VmOperation operation) {
		final VmSchedule entity = new VmSchedule();
		entity.setSubscription(subscription);
		entity.setOperation(operation);
		entity.setCron(cron);
		vmScheduleRepository.saveAndFlush(entity);
	}

	@Override
	@Transactional
	public void afterPropertiesSet() throws SchedulerException {
		// Recreate all schedules from the database
		final List<VmSchedule> schedules = vmScheduleRepository.findAll();
		log.info("Schedules {} jobs from database", schedules.size());
		for (final VmSchedule schedule : schedules) {
			persistTrigger(schedule.getSubscription(), schedule.getCron(), schedule.getOperation());
		}

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
	@POST
	@Path("{subscription:\\d+}/{operation}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public void execute(@PathParam("subscription") final int subscription,
			@PathParam("operation") final VmOperation operation) throws Exception {
		final Subscription entity = subscriptionResource.checkVisibleSubscription(subscription);

		// If the plug-in is no more available or not VmServicePlugin, the
		// execution will fail
		log.info("Operation {} on subscription {}, node {} is manually requested", operation, subscription,
				entity.getNode().getId());
		servicePluginLocator.getResourceExpected(entity.getNode().getId(), VmServicePlugin.class).execute(subscription,
				operation);
		log.info("Operation {} on subscription {}, node {} is manually requested : succeed", operation, subscription,
				entity.getNode().getId());
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
			scedules.add(vo);
		}
		result.setSchedules(scedules);
		return result;
	}

	/**
	 * Update the configuration of subscription of a VM.
	 * 
	 * @param subscription
	 *            The subscription to update.
	 * @param schedule
	 *            The schedule to save or update.
	 */
	@POST
	@PUT
	@Path("{subscription:\\d+}")
	@Transactional
	public void saveOrUpdateSchedule(@PathParam("subscription") final int subscription, final VmScheduleVo schedule)
			throws SchedulerException {
		// Check the subscription is visible
		final Subscription entity = subscriptionResource.checkVisibleSubscription(subscription);

		// Check expressions first
		ValidationJsonException.assertTrue(CronExpression.isValidExpression(schedule.getCron()), "vm-cron", "cron");

		// Clear the schedule
		unschedule(subscription, schedule.getOperation());

		// Persist the new schedules for each provided CRON
		this.schedule(entity, schedule.getCron(), schedule.getOperation());
	}

	/**
	 * Delete the schedule operation from the VM associated to the subscription.
	 * 
	 * @param subscription
	 *            The subscription to update.
	 * @param operation
	 *            The operation to remove from the VM schedules.
	 */
	@DELETE
	@Path("{subscription:\\d+}/{operation}")
	@Transactional
	public void deleteSchedule(@PathParam("subscription") final int subscription,
			@PathParam("operation") final VmOperation operation) throws SchedulerException {
		// Check the subscription is visible
		subscriptionResource.checkVisibleSubscription(subscription);

		// Clear the specific schedule
		unschedule(subscription, operation);
	}
}
