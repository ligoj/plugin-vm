package org.ligoj.app.plugin.vm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.ligoj.app.api.ConfigurablePlugin;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.plugin.AbstractServicePlugin;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.DateUtils;
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
	protected NodeResource nodeResource;

	@Autowired
	protected ServicePluginLocator locator;

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
	 * Remove all schedules of this subscription from the current scheduler, then from the data base.
	 * 
	 * @param subscription
	 *            The parent subscription holding the schedules.
	 */
	protected void unscheduleAll(final int subscription) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> subscription == VmJob.getSubscription(triggerKey));

		// Remove all schedules associated to this subscription
		vmScheduleRepository.deleteAllBy("subscription.id", subscription);
	}

	/**
	 * Remove a schedule of this subscription for a specific operation from the current scheduler, then from the data
	 * base.
	 * 
	 * @throws SchedulerException
	 *             When the schedule cannot be deleted by Quartz.
	 */
	private void unschedule(final int schedule) throws SchedulerException {
		unscheduleQuartz(schedule);

		// Remove all schedules associated to this subscription from persisted
		// entities
		vmScheduleRepository.deleteById(schedule);
	}

	private void unscheduleQuartz(final int schedule) throws SchedulerException {
		// Remove current schedules from the memory
		unscheduleAll(triggerKey -> schedule == VmJob.getSchedule(triggerKey));
	}

	/**
	 * Remove all schedules matching the given predicate from the current scheduler, then from the data base.
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
		final List<VmSchedule> schedules = vmScheduleRepository.findAll();
		log.info("Schedules {} jobs from database", schedules.size());
		for (final VmSchedule schedule : schedules) {
			persistTrigger(schedule);
		}

	}

	/**
	 * Execute a {@link VmOperation} to the associated VM and checks its visibility against the current principal user.
	 * This a synchronous call, but the effective execution is delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} identifier associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 * @return The execution identifier. Only useful for the correlation. May be <code>null</code> when skipped.
	 */
	@POST
	@Path("{subscription:\\d+}/{operation}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public Integer execute(@PathParam("subscription") final int subscription,
			@PathParam("operation") final VmOperation operation) {
		return execute(subscriptionResource.checkVisibleSubscription(subscription), operation);
	}

	/**
	 * Execute a {@link VmOperation} to the associated VM. This a synchronous call, but the effective execution is
	 * delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 * @return The execution identifier. Only useful for the correlation. May be <code>null</code> when skipped.
	 */
	@Transactional
	public Integer execute(final Subscription subscription, final VmOperation operation) {
		final String node = subscription.getNode().getId();
		final String trigger = securityHelper.getLogin();
		log.info("Operation {} on subscription {}, node {} is requested by {}", operation, subscription.getId(), node,
				trigger);
		final VmExecution execution = new VmExecution();
		execution.setOperation(operation);
		execution.setSubscription(subscription);
		execution.setTrigger(trigger);
		execution.setDate(new Date());
		try {
			// Execute the operation if plug-in still available
			locator.getResourceExpected(node, VmServicePlugin.class).execute(execution);
			log.info("Operation {} (->{}) on subscription {}, node {} : succeed", operation, execution.getOperation(),
					subscription.getId(), node);
			execution.setSucceed(true);
		} catch (final Exception e) {
			// Something goes wrong for this VM, this log would be considered for reporting
			execution.setError(e.getMessage());
			log.error("Operation {} on subscription {}, node {} : failed", operation, subscription.getId(), e);
		} finally {
			// Save as needed
			saveAndFlush(execution, operation);
		}
		return execution.getId();
	}

	/**
	 * Save as needed the given schedule.
	 * 
	 * @param execution
	 *            The execution to persist.
	 * @param operation
	 *            The original operation to execute.
	 */
	private void saveAndFlush(final VmExecution execution, final VmOperation operation) {
		// Check this execution has been really been executed
		if (execution.getOperation() == null) {
			log.info("Operation {} on subscription {} : skipped", operation, execution.getSubscription().getId());
		} else {
			// Persist the execution result
			vmExecutionRepository.saveAndFlush(execution);
		}
	}

	@GET
	@Path("{subscription:\\d+}")
	@Override
	@Transactional
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public VmConfigurationVo getConfiguration(@PathParam("subscription") final int subscription) throws ParseException {
		// Check the subscription is visible
		final Subscription entity = subscriptionResource.checkVisibleSubscription(subscription);

		// Get the details
		final VmConfigurationVo result = new VmConfigurationVo();
		final List<VmScheduleVo> scedules = new ArrayList<>();
		final Date now = DateUtils.newCalendar().getTime();
		for (final VmSchedule schedule : vmScheduleRepository.findBySubscription(subscription)) {
			// Copy basic attributes
			final VmScheduleVo vo = new VmScheduleVo();
			vo.setCron(schedule.getCron());
			vo.setOperation(schedule.getOperation());
			vo.setId(schedule.getId());
			vo.setNext(new CronExpression(schedule.getCron()).getNextValidTimeAfter(now));
			scedules.add(vo);
		}
		result.setSchedules(scedules);

		// Add snapshot capability
		result.setSupportSnapshot(locator.getResource(entity.getNode().getId(), Snapshotting.class) != null);
		return result;
	}

	/**
	 * Return the execution report of VM related to the given subscription.
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @param file
	 *            The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{subscription:\\d+}/{file:history-.*.csv}")
	public Response downloadHistoryReport(@PathParam("subscription") final int subscription,
			@PathParam("file") final String file) {
		subscriptionResource.checkVisibleSubscription(subscription);
		return AbstractToolPluginResource
				.download(o -> writeHistory(o, vmExecutionRepository.findAllBy("subscription.id", subscription)), file)
				.build();
	}

	/**
	 * Write all executions related to given subscription, from the oldest to the newest.
	 */
	private void writeHistory(final OutputStream output, Collection<VmExecution> executions) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final FastDateFormat df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		writer.write(
				"dateHMS;timestamp;operation;subscription;project;projectKey;projectName;node;vm;trigger;succeed;statusText;errorText");
		for (final VmExecution execution : executions) {
			final Project project = execution.getSubscription().getProject();
			writer.write('\n');
			writer.write(df.format(execution.getDate()));
			writer.write(';');
			writer.write(String.valueOf(execution.getDate().getTime()));
			writer.write(';');
			writer.write(execution.getOperation().name());
			writer.write(';');
			writer.write(String.valueOf(execution.getSubscription().getId()));
			writer.write(';');
			writer.write(String.valueOf(project.getId()));
			writer.write(';');
			writer.write(project.getPkey());
			writer.write(';');
			writer.write(project.getName().replaceAll("\"", "'"));
			writer.write(';');
			writer.write(execution.getSubscription().getNode().getId());
			writer.write(';');
			writer.write(StringUtils.defaultString(execution.getVm(), ""));
			writeExecutionStatus(writer, execution);
		}

		// Ensure buffer is flushed
		writer.flush();
	}

	/**
	 * Return all configured schedules report of all VM related to a a visible subscription related to the given node.
	 * 
	 * @param node
	 *            The related node.
	 * @param file
	 *            The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{node:service:.+}/{file:schedules-.*.csv}")
	public Response downloadNodeSchedulesReport(@PathParam("node") final String node,
			@PathParam("file") final String file) {
		// Get all visible schedules linked to this node
		final List<VmSchedule> schedules = vmScheduleRepository.findAllByNode(node, securityHelper.getLogin());

		// Get all last execution related to given node, Key is the subscription identifier
		final Map<Integer, VmExecution> lastExecutions = vmExecutionRepository.findAllByNodeLast(node).stream()
				.collect(Collectors.toMap(e -> e.getSubscription().getId(), Function.identity()));

		// Get all last executions of all schedules
		return AbstractToolPluginResource.download(o -> writeSchedules(o, schedules, lastExecutions), file).build();
	}

	/**
	 * Write all schedules.
	 */
	private void writeSchedules(final OutputStream output, Collection<VmSchedule> schedules,
			final Map<Integer, VmExecution> lastExecutions) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final FastDateFormat df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		final Date now = DateUtils.newCalendar().getTime();
		writer.write(
				"operation;subscription;project;projectKey;projectName;node;vm;lastDateHMS;lastTimestamp;lastTrigger;lastSucceed;lastStatusText;lastErrorText;nextDateHMS;nextTimestamp");
		for (final VmSchedule schedule : schedules) {
			// The last execution of the related schedule
			final VmExecution execution = lastExecutions.get(schedule.getSubscription().getId());

			final Project project = schedule.getSubscription().getProject();
			writer.write('\n');
			writer.write(schedule.getOperation().name());
			writer.write(';');
			writer.write(String.valueOf(schedule.getSubscription().getId()));
			writer.write(';');
			writer.write(String.valueOf(project.getId()));
			writer.write(';');
			writer.write(project.getPkey());
			writer.write(';');
			writer.write(project.getName().replaceAll("\"", "'"));
			writer.write(';');
			writer.write(schedule.getSubscription().getNode().getId());
			if (execution == null) {
				writer.write(";;;;;;;");
			} else {
				// Last execution
				writer.write(';');
				writer.write(StringUtils.defaultString(execution.getVm(), ""));
				writer.write(';');
				writer.write(df.format(execution.getDate()));
				writer.write(';');
				writer.write(String.valueOf(execution.getDate().getTime()));
				writeExecutionStatus(writer, execution);
			}

			// Next execution
			try {
				final Date next = new CronExpression(schedule.getCron()).getNextValidTimeAfter(now);
				writer.write(';');
				writer.write(df.format(next));
				writer.write(';');
				writer.write(String.valueOf(next.getTime()));
			} catch (final ParseException pe) {
				// Non blocking error
				log.error("Invalid CRON expression {}", schedule.getCron());
				writer.write(";ERROR;ERROR");
			}
		}

		// Ensure buffer is flushed
		writer.flush();
	}

	/**
	 * Write <code>trigger;succeed;statusText;errorText</code> execution values.
	 * 
	 * @throws IOException
	 *             When could not write the execution values.
	 */
	private void writeExecutionStatus(final Writer writer, final VmExecution execution) throws IOException {
		writer.write(';');
		writer.write(execution.getTrigger());
		writer.write(';');
		writer.write(String.valueOf(execution.isSucceed()));
		writer.write(';');
		writer.write(StringUtils.defaultString(execution.getStatusText(), ""));
		writer.write(';');
		writer.write(StringUtils.defaultString(execution.getError(), ""));
	}

	/**
	 * Create a new schedule.
	 * 
	 * @param schedule
	 *            The schedule to save or update. The CRON expression may be either in the 5 either in 6 parts. The
	 *            optional 6th corresponds to the "seconds" and will be prepended to the expression to conform to Quartz
	 *            format.
	 * @return The created schedule identifier.
	 * @throws SchedulerException
	 *             When the schedule cannot be done by Quartz.
	 */
	@POST
	@Transactional
	public int createSchedule(final VmScheduleVo schedule) throws SchedulerException {
		return persistTrigger(checkAndSaveSchedule(schedule, new VmSchedule())).getId();
	}

	/**
	 * Update an existing schedule.
	 * 
	 * @param schedule
	 *            The schedule to save or update. The CRON expression may be either in the 5 either in 6 parts. The
	 *            optional 6th corresponds to the "seconds" and will be prepended to the expression to conform to Quartz
	 *            format.
	 * @throws SchedulerException
	 *             When the schedule cannot be done by Quartz.
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
	 *            The schedule identifier to delete.
	 * @throws SchedulerException
	 *             When the schedule cannot be done by Quartz.
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
