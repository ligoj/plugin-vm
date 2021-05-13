/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.schedule;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.persistence.EntityNotFoundException;
import javax.transaction.Transactional;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
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
 * The Virtual Machine service : schedule operation.
 */
@Service
@Path(VmResource.SERVICE_URL + "/{subscription:\\d+}/schedule")
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmScheduleResource implements InitializingBean {

	/**
	 * Group name used for all triggers.
	 */
	public static final String SCHEDULE_TRIGGER_GROUP = "vm-schedule";

	@Autowired
	private JobDetailFactoryBean vmJobDetailFactoryBean;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	@Autowired
	private VmScheduleRepository repository;

	@Autowired
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected NodeResource nodeResource;

	@Autowired
	protected ServicePluginLocator locator;

	@Autowired
	protected SecurityHelper securityHelper;

	@Autowired
	private VmExecutionRepository vmExecutionRepository;

	/**
	 * Remove all schedules from memory, Quartz and database.
	 *
	 * @param subscription The parent subscription holding the schedules.
	 * @throws SchedulerException When quartz cannot remove the schedules.
	 */
	@Transactional
	public void delete(final int subscription) throws SchedulerException {
		unscheduleAll(subscription);

		// Also remove execution history
		vmExecutionRepository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Delete the schedule entity. Related subscription's visibility is checked.
	 *
	 * @param subscription The target subscription. The subscription cannot be changed for a schedule.
	 * @param schedule     The schedule identifier to delete.
	 * @throws SchedulerException When the schedule cannot be done by Quartz.
	 */
	@DELETE
	@Path("{id:\\d+}")
	@Transactional
	public void delete(@PathParam("subscription") final int subscription, @PathParam("id") final int schedule)
			throws SchedulerException {
		// Check the subscription is visible
		subscriptionResource.checkVisible(subscription);

		// Check the schedule is related to the subscription
		checkOwnership(subscription, schedule);

		// Clear the specific schedule
		unschedule(schedule);
	}

	/**
	 * Remove a schedule of this subscription for a specific operation from the current scheduler, then from the data
	 * base.
	 *
	 * @param schedule The schedule to remove from quartz.
	 * @throws SchedulerException When the schedule cannot be deleted by Quartz.
	 */
	private void unschedule(final int schedule) throws SchedulerException {
		unscheduleQuartz(schedule);

		// Remove all schedules associated to this subscription from persisted entities
		repository.deleteById(schedule);
	}

	private void unscheduleQuartz(final int schedule) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> schedule == VmJob.getSchedule(triggerKey));
	}

	/**
	 * Remove all schedules of this subscription from the current scheduler, then from the data base.
	 *
	 * @param subscription The parent subscription holding the schedules.
	 * @throws SchedulerException When quartz cannot remove the schedules.
	 */
	protected void unscheduleAll(final int subscription) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> subscription == VmJob.getSubscription(triggerKey));

		// Remove all schedules associated to this subscription
		repository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Remove all schedules matching the given predicate from the current scheduler, then from the data base.
	 */
	private void unscheduleAll(final Predicate<TriggerKey> predicate) throws SchedulerException {
		// Remove current schedules from the memory
		final var scheduler = vmSchedulerFactoryBean.getObject();
		for (final var triggerKey : scheduler.getTriggerKeys(GroupMatcher.groupEquals(SCHEDULE_TRIGGER_GROUP))) {
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
		final var id = VmJob.format(schedule);
		final var object = (JobDetailImpl) vmJobDetailFactoryBean.getObject();
		object.getJobDataMap().put("vmServicePlugin", this);
		final Trigger trigger = TriggerBuilder.newTrigger().withIdentity(id, SCHEDULE_TRIGGER_GROUP)
				.withSchedule(CronScheduleBuilder.cronSchedule(schedule.getCron())
						.inTimeZone(DateUtils.getApplicationTimeZone()))
				.forJob(object).usingJobData("subscription", schedule.getSubscription().getId())
				.usingJobData("operation", schedule.getOperation().name()).usingJobData("schedule", schedule.getId())
				.build();

		// Add this trigger
		vmSchedulerFactoryBean.getObject().scheduleJob(trigger);
		return schedule;
	}

	@Override
	@Transactional
	public void afterPropertiesSet() throws SchedulerException {
		// Recreate all schedules from the database
		final var schedules = repository.findAll();
		log.info("Schedules {} jobs from database", schedules.size());
		for (final VmSchedule schedule : schedules) {
			persistTrigger(schedule);
		}
	}

	/**
	 * Return all schedules related to given subscription.
	 *
	 * @param subscription The subscription identifier.
	 * @return All schedules related to given subscription.
	 * @throws ParseException When CRON cannot be parsed.
	 */
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public List<VmScheduleVo> findAll(final int subscription) throws ParseException {
		final List<VmScheduleVo> schedules = new ArrayList<>();
		final var now = DateUtils.newCalendar().getTime();
		for (final VmSchedule schedule : repository.findBySubscription(subscription)) {
			// Copy basic attributes
			final var vo = new VmScheduleVo();
			vo.setCron(schedule.getCron());
			vo.setOperation(schedule.getOperation());
			vo.setId(schedule.getId());
			vo.setNext(new CronExpression(schedule.getCron()).getNextValidTimeAfter(now));
			schedules.add(vo);
		}
		return schedules;
	}

	/**
	 * Create a new schedule.
	 *
	 * @param subscription The target subscription. The subscription cannot be changed for a schedule.
	 * @param schedule     The schedule to save or update. The CRON expression may be either in the 5 either in 6 parts.
	 *                     The optional 6th corresponds to the "seconds" and will be prepended to the expression to
	 *                     conform to Quartz format.
	 * @return The created schedule identifier.
	 * @throws SchedulerException When the schedule cannot be done by Quartz.
	 */
	@POST
	@Transactional
	public int create(@PathParam("subscription") final int subscription, final VmScheduleVo schedule)
			throws SchedulerException {
		return persistTrigger(checkAndSave(subscription, schedule, new VmSchedule())).getId();
	}

	/**
	 * Update an existing schedule.
	 *
	 * @param subscription The target subscription. The subscription cannot be changed for a schedule.
	 * @param schedule     The schedule to save or update. The CRON expression may be either in the 5 either in 6 parts.
	 *                     The optional 6th corresponds to the "seconds" and will be prepended to the expression to
	 *                     conform to Quartz format.
	 * @throws SchedulerException When the schedule cannot be done by Quartz.
	 */
	@PUT
	@Transactional
	public void update(@PathParam("subscription") final int subscription, final VmScheduleVo schedule)
			throws SchedulerException {
		// Check the schedule is related to the subscription
		final var entity = checkOwnership(subscription, schedule.getId());

		// Persist the new schedules for each provided CRON
		checkAndSave(subscription, schedule, entity);

		// Remove current schedules from the Quartz memory
		unscheduleQuartz(schedule.getId());

		persistTrigger(checkAndSave(subscription, schedule, entity));
	}

	private VmSchedule checkAndSave(final int subscription, final VmScheduleVo schedule, final VmSchedule entity) {
		// Check the subscription is visible
		final var subscriptionEntity = subscriptionResource.checkVisible(subscription);

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

		entity.setSubscription(subscriptionEntity);
		entity.setOperation(schedule.getOperation());
		entity.setCron(schedule.getCron());

		// Persist the new schedules for each provided CRON
		repository.saveAndFlush(entity);
		return entity;
	}

	/**
	 * Check the given schedule exists and is related to given subscription.
	 *
	 * @param subscription The subscription holder.
	 * @param schedule     The target schedule identifier.
	 * @return The resolved schedule when found and owned by the subscription.
	 */
	private VmSchedule checkOwnership(final int subscription, final int schedule) {
		final var entity = repository.findOneExpected(schedule);
		if (entity.getSubscription().getId().intValue() != subscription) {
			throw new EntityNotFoundException(String.valueOf(schedule));
		}
		return entity;
	}
}
