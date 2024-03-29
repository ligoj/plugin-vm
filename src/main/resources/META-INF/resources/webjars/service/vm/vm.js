/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
/*jshint esversion: 6*/
define(function () {
	var current = {

		/**
		 * VM schedule table.
		 */
		table: null,

		/**
		 * VM snapshot table.
		 */
		snapshotTable: null,

		/**
		 * Current configuration.
		 */
		model: null,

		/**
		 * Current identifier being updated.
		 */
		currentId: 0,

		/**
		 * Managed status.
		 */
		vmStatus: {
			powered_on: 'fas fa-play text-success',
			powered_off: 'fas fa-stop text-danger',
			suspended: 'fas fa-pause text-warning'
		},

		/**
		 * Managed operations.
		 */
		vmOperations: {
			OFF: 'fas fa-power-off',
			ON: 'fas fa-play',
			SUSPEND: 'fas fa-pause',
			SHUTDOWN: 'fas fa-stop',
			RESET: 'fas fa-redo-alt',
			REBOOT: 'fas fa-sync-alt'
		},

		initialize: function () {
			current.$super('$view').off('click.service-vm-operation').on('click.service-vm-operation', '.subscriptions .service-vm-operation', current.serviceVmOperation);
		},

		/**
		 * Synchronize the VM configuration UI with the retrieved data
		 */
		configure: function (configuration) {
			current.model = configuration;

			// Configure schedules
			require(['later/later.mod', 'pretty-cron/pretty-cron'], function (later) {
				current.initializeVmConfiguration();
				current.later = later;
				later.date.localTime();
				_('subscribe-configuration-vm').removeClass('hide');
			});

			// Configure snapshot if supported
			if (current.model.configuration.supportSnapshot) {
				_('vm-snapshot-tab-trigger').off('shown.bs.tab').one('shown.bs.tab', function () {
					current.initializeVmSnapshotsTable();
				}).closest('li').removeClass('active').closest('.nav').removeClass('hidden');
			} else {
				_('vm-snapshot-tab-trigger').closest('li').removeClass('active').closest('.nav').addClass('hidden');
			}
			_('vm-snapshot-tab').removeClass('active');
			_('vm-schedule-tab').addClass('active');
		},

		initializeVmSnapshotsTable: function () {
			current.snapshotTable && current.snapshotTable.fnDestroy();
			current.snapshotTable = _('vm-snapshots').dataTable({
				dom: '<"row"<"col-xs-6"B><"col-xs-6"f>r>t<"row"<"col-xs-6"i><"col-xs-6"p>>',
				serverSide: false,
				searching: true,
				destroy: true,
				order: [
					[0, 'desc']
				],
				ajax: {
					url: REST_PATH + 'service/vm/' + current.model.id + '/snapshot',
					dataSrc: ''
				},
				columns: [{
					data: 'date',
					className: 'responsive-datetime',
					render: date => moment(date).format(formatManager.messages.shortDateMomentJs + ' HH:mm:ss')
				}, {
					data: 'id'
				}, {
					data: 'volumes',
					render: function (volumes, mode) {
						var total = 0;
						if (volumes && volumes.length) {
							// Add volume details : size, name, id
							var result = ' <span class="small">(' + volumes.length + ')</span> ';
							volumes.forEach(v => {
								total += v.size || 0;
								result += ' <span data-toggle="tooltip" title="Id: ' + v.id + '<br>Name: ' + v.name + '"><i class="fas fa-database"></i> ' + formatManager.formatSize(v.size * 1024 * 1024 * 1024, 3) + ' ' + v.name + '</span>';
							});
							if (mode === 'display') {
								return formatManager.formatSize(total * 1024 * 1024 * 1024, 3) + result;
							}
						} else {
							// No disk...
							if (mode === 'display') {
								return current.$messages['service:vm:snapshot-no-volume'];
							}
						}
						return total;
					}
				}, {
					data: 'statusText',
					render: function (status, mode, data) {
						if (mode === 'display') {
							status = (status && current.$messages['service:vm:snapshot-status-' + status]) || status;
							if (current.isPending(data)) {
								return '<i class="fas fa-circle-notch fa-spin"></i> ' + status;
							}
							if (data.available) {
								return '<i class="fas fa-check text-success"></i> ' + status;
							}
							return '<i class="fas fa-times text-danger"></i> ' + status;
						}
						return status;
					}
				}, {
					data: null,
					orderable: false,
					width: '40px',
					render: function (_i, _j, data) {
						return data.pending ? '' : ('<a class="delete"><i class="fas fa-trash-alt" data-toggle="tooltip" title="' + current.$messages['service:vm:snapshot-delete'] + '"></i></a>');
					}
				}],
				buttons: [{
					extend: 'collection',
					className: 'btn-success vm-snapshot-create disabled',
					text: current.$messages['service:vm:snapshot-create'],
					autoClose: true,
					buttons: [{
						className: 'vm-snapshot-create-no-stop',
						text: current.$messages['service:vm:snapshot-create-no-stop'] + '<i class="fas fa-info-circle text-info pull-right" data-toggle="tooltip" title="' + current.$messages['service:vm:snapshot-create-no-stop-help'] + '"></i>',
						action: current.createSnapshot
					}, {
						className: 'vm-snapshot-create-stop',
						text: current.$messages['service:vm:snapshot-create-stop'] + '<i class="fas fa-info-circle text-info pull-right" data-toggle="tooltip" title="' + current.$messages['service:vm:snapshot-create-stop-help'] + '"></i>',
						action: current.createSnapshot
					}]
				}],
				initComplete: function (_i, snapshots) {
					var subscription = current.model;
					if (current.hasPendingSnapshot(snapshots)) {
						// At least one snapshot is pending: track it
						current.disableSnapshot();
						setTimeout(function () {
							// Poll the unfinished snapshot
							current.pollStart('snapshot-' + subscription.id, subscription, current.synchronizeSnapshot);
						}, 10);
					} else {
						current.enableSnapshot();
					}
				}
			});
		},

		/**
		 * Return true when there is at least one pending snapshot.
		 */
		hasPendingSnapshot: snapshots => snapshots.some(current.isPending),

		/**
		 * Return true when the given snapshot is not in a stable operation.
		 */
		isPending: snapshot => snapshot.pending || snapshot.operation === 'delete',

		/**
		 * Create a snapshot.
		 */
		createSnapshot: function () {
			current.disableSnapshot();
			var subscription = current.model;
			var stop = $(e.target).closest('li').is('.vm-snapshot-create-stop');
			$.ajax({
				type: 'POST',
				url: REST_PATH + 'service/vm/' + subscription.id + '/snapshot?stop=' + stop,
				dataType: 'json',
				contentType: 'application/json',
				success: function (data) {
					notifyManager.notify(Handlebars.compile(current.$messages.created)(data.id));
					current.pollStart('snapshot-' + subscription.id, subscription, current.synchronizeSnapshot);
				},
				error: () => current.enableSnapshot()
			});
		},

		initializeSchedulesTable: function () {
			current.table && current.table.fnDestroy();
			current.table = _('vm-schedules').dataTable({
				dom: '<"row"<"col-xs-6"B><"col-xs-6"f>r>t<"row"<"col-xs-6"i><"col-xs-6"p>>',
				data: current.model.configuration.schedules,
				columns: [{
					data: 'operation',
					render: function (operation) {
						var label = current.$messages['service:vm:' + operation.toLowerCase()];
						var help = current.$messages['service:vm:' + operation.toLowerCase() + '-help'];
						return '<i class="' + current.vmOperations[operation.toUpperCase()] + '" data-html="true" data-toggle="tooltip" title="' + label + '<br>' + help + '" data-container="#_ucDiv"></i><span class="hidden-xs hidden-sm"> ' + label + '</span>';
					}
				}, {
					data: 'cron',
					className: 'vm-schedules-cron'
				}, {
					data: null,
					className: 'vm-schedules-description hidden-sm hidden-xs',
					render: (_i, _j, data) => prettyCron.toString(data.cron, true)
				}, {
					data: 'next',
					className: 'vm-schedules-next responsive-datetime',
					render: (_i, _j, data) => moment(later.schedule(later.parse.cron(data.cron, true)).next(1)).format(formatManager.messages.shortDateMomentJs + ' HH:mm:ss')
				}, {
					data: null,
					orderable: false,
					width: '40px',
					render: function () {
						var result = '<a class="update" data-toggle="modal" data-target="#vm-schedules-popup"><i class="fas fa-pencil-alt" data-toggle="tooltip" title="';
						result += current.$messages.update;
						result += '"></i></a> <a class="delete"><i class="fas fa-trash-alt" data-toggle="tooltip" title="' + current.$messages['delete'] + '"></i></a>';
						return result;
					}
				}],
				destroy: true,
				buttons: [{
					extend: 'popup',
					target: '#vm-schedules-popup',
					className: 'btn-success btn-raised'
				}, {
					extend: 'collection',
					text: current.$messages['service:vm:schedule:reports'],
					autoClose: true,
					buttons: current.generateReportButtons(current.model)
				}]
			});
		},

		/**
		 * Return a new DataTables buttons collection configuration.
		 * @param {object} model The model to render.
		 * @return DataTables buttons collection configuration.
		 */
		generateReportButtons: function (model) {
			var buttons = [{
				// Add history download button
				'extend': 'dropdown-link',
				'text': current.$messages['service:vm:schedule:reports:executions'],
				'attr': {
					'title': current.$messages['service:vm:schedule:reports:executions-help'],
					'data-toggle': 'tooltip'
				},
				'link-attr': {
					'href': REST_PATH + 'service/vm/' + model.subscription + '/execution/executions-' + model.subscription + '.csv',
					'download': 'download'
				}
			}];
			buttons.push(current.newReportScheduleButton(model.node));

			if (model.node.refined) {
				// Add the tool level
				buttons.push(current.newReportScheduleButton(model.node.refined));
				if (model.node.refined.refined) {
					// Add the service level
					buttons.push(current.newReportScheduleButton(model.node.refined.refined));
				}
			}
			return buttons;
		},

		/**
		 * Return a new DataTables button for a given node.
		 * @param {object} node The node to render.
		 * @return DataTables button configuration.
		 */
		newReportScheduleButton: function (node) {
			return {
				// Add schedules history download button
				extend: 'dropdown-link',
				text: Handlebars.compile(current.$messages['service:vm:schedule:reports:schedules'])(node.name),
				attr: {
					'title': current.$messages['service:vm:schedule:reports:schedules-help'],
					'data-toggle': 'tooltip'
				},
				'link-attr': {
					'href': REST_PATH + 'service/vm/' + node.id + '/schedules-' + node.id.replace(/:/g, '-') + '.csv',
					'download': 'download'
				}
			};
		},

		getNextExecution: function (cron) {
			return cron ? moment(later.schedule(later.parse.cron(cron, true)).next(1))
				.format(formatManager.messages.shortDateMomentJs + ' HH:mm:ss') : '';
		},

		/**
		 * Operation format.
		 */
		formatOperation: function (operation) {
			return '<i class="' + current.vmOperations[operation.toUpperCase()] + '"></i> ' + current.$messages['service:vm:' + operation.toLowerCase()];
		},

		/**
		 * Initialize VM configuration UI components
		 */
		initializeVmConfiguration: function () {
			current.initializeSchedulesTable();

			var operations = Object.keys(current.vmOperations).map(operation => ({
				id: operation,
				text: current.formatOperation(operation)
			}));

			_('operation').select2({
				formatSelection: current.formatOs,
				formatResult: current.formatOs,
				escapeMarkup: m => m,
				data: operations
			});

			// Next schedule preview
			_('cron').on('change', function () {
				var cron = $(this).val();
				if (cron.split(' ').length === 6) {
					cron += ' *';
				}
				try {
					_('cron-next').val(current.getNextExecution(cron));
				} catch (err) {
					// Ignore for now
				}
			});
			// VM operation schedule helper in popup
			_('vm-schedules-popup').on('show.bs.modal', function (event) {
				validationManager.reset($(this));
				var $source = $(event.relatedTarget);
				var $tr = $source.closest('tr');
				var schedule = ($tr.length && current.table.fnGetData($tr[0])) || {};
				current.currentId = schedule.id;
				var op = schedule.operation;
				_('operation').select2('data', op ? {
					id: op,
					text: current.formatOperation(op)
				} : null);
				_('cron').val(schedule.cron || '0 0 0 * * ?').trigger('change');
				require(['i18n!jqcron/nls/messages', 'jqcron/jqcron', 'css!jqcron/jqcron'], function (messages) {
					_('cron').jqCron({
						enabled_second: true,
						enabled_minute: true,
						multiple_dom: true,
						multiple_month: true,
						multiple_mins: true,
						multiple_secs: true,
						multiple_dow: true,
						multiple_time_hours: true,
						multiple_time_minutes: true,
						multiple_time_seconds: true,
						default_period: 'week',
						default_value: schedule.cron,
						no_reset_button: false,
						texts: {
							'default': messages
						},
						lang: 'default'
					});
				});
			}).on('shown.bs.modal', function () {
				// Focus to the most wanted component depending on the state
				if (current.currentId) {
					_('cron').trigger('focus');
				} else {
					_('operation').select2('focus');
				}
			}).on('submit', function (e) {
				e.preventDefault();
				current.saveSchedule(current.formToObject());
				return false;
			});
			_('vm-schedules').on('click', 'tr .delete', function () {
				current.deleteSchedule(current.table.fnGetData($(this).closest('tr')[0]));
			});
			_('vm-snapshots').on('click', 'tr .delete', function () {
				var snapshot = current.snapshotTable.fnGetData($(this).closest('tr')[0]);
				snapshot.operation = 'delete';
				snapshot.statusText = 'deleting';
				current.redrawSnapshot(snapshot.id);
				current.deleteSnapshot(snapshot);
			});
		},

		redrawSnapshot: function (id, callback) {
			var found = false;
			_('vm-snapshots').DataTable().rows(function (index, data) {
				if (data.id === id) {
					found = true;
					if (callback) {
						callback(data);
					}
					return true;
				}
				return false;
			}).invalidate().draw();
			return found;
		},

		formToObject: () => ({
			id: current.currentId,
			cron: _('cron').val(),
			operation: _('operation').val().toLowerCase()
		}),

		/**
		 * Render VM operations and scheduler.
		 */
		renderFeatures: subscription => `<a href="#/home/project/${subscription.project}/subscription/${subscription.id}" class="feature configure-trigger" data-toggle="tooltip" title="${current.$messages.configure}"><i class="fas fa-cog"></i></a>`,

		/**
		 * Display the status of the VM
		 */
		renderDetailsFeatures: function (subscription, $tr, $features) {
			var status = subscription.data.vm.status.toLowerCase();
			var busy = subscription.data.vm.busy;
			var deployed = status === 'powered_off' && subscription.data.vm.deployed;
			if (subscription.data.schedules) {
				// At least on schedule
				$features.find('.configure-trigger').tooltip('hide').attr('title', current.$messages['configure-present']).addClass('text-danger');
			} else {
				$features.find('.configure-trigger').tooltip('hide').attr('title', current.$messages.configure).removeClass('text-danger');
			}

			// Complete the operation menu
			if ($features.has('.feature.operations').length === 0) {
				current.renderVmOperationButtons($features);
			}

			return '<i data-toggle="tooltip" data-html="true" title="' + (current.$messages['service:vm:' + status] || status) +
				(busy ? ' (' + current.$messages['service:vm:busy'] + ')' : '') +
				(deployed ? '<br>[' + current.$messages['service:vm:deployed'] + ']' : '') + '" class="' +
				(current.vmStatus[status] || 'far fa-question-circle text-muted') +
				(busy ? ' faa-flash animated' : '') + (deployed ? ' deployed' : '') + ' fa-fw service-vm-status"></i>';
		},

		renderVmOperationButtons: function ($features) {
			var operationHtml = `<div class="btn-group btn-link feature dropdown operations dropup" data-container="body" data-toggle="tooltip" title="${current.$messages['service:vm:operation']}"><i class="fas fa-power-off dropdown-toggle" data-toggle="dropdown"></i><ul class="dropdown-menu dropdown-menu-right">`;

			// Add Off,On,Shutdown,Reset,Reboot,Suspend
			for (const [operation, classes] of Object.entries(current.vmOperations)) {
				operationHtml += '<li>' + current.renderServiceServiceVmOperationButton(classes, operation) + '</li>';
			}
			operationHtml += '</ul></div>';
			$features.find('.feature').eq(0).before($(operationHtml));
		},

		renderServiceServiceVmOperationButton: function (icon, operation) {
			return `<a class="feature service-vm-operation service-vm-operation-${operation.toLowerCase()}" data-operation="${operation}" data-toggle="tooltip" title="${current.$messages['service:vm:' +
				operation.toLowerCase() + '-help']}"><i class="fa-fw menu-icon ${icon}"></i> ${current.$messages['service:vm:' +
				operation.toLowerCase()]}</a>`;
		},

		/**
		 * Return network details
		 */
		renderNetwork: function (networks) {
			var result = [];
			var networkTypeToIcon = {
				'public': 'globe',
				'private': 'lock'
			};
			networks.forEach(function (network) {
				result.push(`<i class="fas fa-${networkTypeToIcon[network.type] || 'slash'}"></i> ${network.ip}${network.dns ? ' [' + network.dns + ']' : ''}`);
			});

			return result.join(', ');
		},

		/**
		 * Delete a scheduled operation.
		 * @param {object} Schedule to delete.
		 */
		deleteSchedule: function (schedule) {
			var subscription = current.model.subscription;
			$.ajax({
				type: 'DELETE',
				url: REST_PATH + 'service/vm/' + subscription + '/schedule/' + schedule.id,
				dataType: 'json',
				contentType: 'application/json',
				success: function () {
					notifyManager.notify((Handlebars.compile(current.$messages.deleted))(subscription + ' : ' + schedule.operation.toUpperCase()));
					current.reload();
				}
			});
		},

		/**
		 * Delete a snapshot.
		 * @param {object} snapshot Snapshot to delete.
		 */
		deleteSnapshot: function (snapshot) {
			current.disableSnapshot();
			var subscription = current.model;
			$.ajax({
				type: 'DELETE',
				url: REST_PATH + 'service/vm/' + subscription.id + '/snapshot/' + snapshot.id,
				dataType: 'json',
				contentType: 'application/json',
				success: function () {
					notifyManager.notify(Handlebars.compile(current.$messages.deleted)(snapshot.id));
					current.pollStart('snapshot-' + subscription.id, subscription, current.synchronizeSnapshot);
				},
				error: function () {
					current.enableSnapshot();
				}
			});
		},

		/**
		 * Save or update a schedule.
		 * @param {Object} schedule to update/save : operation+CRON
		 */
		saveSchedule: function (schedule) {
			var subscription = current.model.subscription;
			$.ajax({
				type: schedule.id ? 'PUT' : 'POST',
				url: REST_PATH + 'service/vm/' + subscription + '/schedule',
				dataType: 'json',
				contentType: 'application/json',
				data: JSON.stringify(schedule),
				success: function (data) {
					notifyManager.notify(Handlebars.compile(current.$messages[schedule.id ? 'updated' : 'created'])((schedule.id || data) + ' : ' + schedule.operation.toUpperCase()));
					current.reload();
					_('vm-schedules-popup').modal('hide');
				}
			});
		},

		/**
		 * Restore selected snapshot.
		 * @param {Object} schedule to update/save : operation+CRON
		 */
		restoreSnapshot: function (snapshot) {
			$.ajax({
				type: 'PUT',
				url: REST_PATH + 'service/vm/' + subscription + '/snapshot/' + snapshot.id,
				dataType: 'json',
				contentType: 'application/json',
				success: function () {
					notifyManager.notify(Handlebars.compile(current.$messages['service:vm:restoring'])([snapshot.name, snapshot.id]));
					current.reload();
					_('vm-snapshot-popup').modal('hide');
				}
			});
		},

		/**
		 * Reload the model
		 */
		reload: function () {
			// Clear the table
			var $schedules = _('vm-schedules').DataTable();
			$schedules.clear().draw();
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'subscription/' + current.model.subscription + '/configuration',
				contentType: 'application/json',
				type: 'GET',
				success: function (data) {
					current.model = data;
					$schedules.rows.add(current.model.configuration.schedules).draw();
				}
			});
		},

		/**
		 * Execute a VM operation.
		 * @param confirm {boolean}     When 'true' the execution request is sent immediately Otherwise the confirmation popup is show.
		 * @param $button {jquery}      The related trigger button.
		 * @param subscription {object} The subscription details.
		 * @param operation {string}    The operation name.
		 */
		serviceVmOperation: function (confirm, $button, subscription, operation) {
			if (confirm === true) {
				var id = subscription.id;
				$button.attr('disabled', 'disabled').find('.fa').addClass('faa-flash animated');
				$.ajax({
					dataType: 'json',
					url: REST_PATH + 'service/vm/' + id + '/execution/' + operation,
					contentType: 'application/json',
					type: 'POST',
					success: function () {
						notifyManager.notify(Handlebars.compile(current.$messages['vm-operation-success'])([id, current.$messages['service:vm:' + operation.toLowerCase()]]));
						current.pollStart('execution-' + id, subscription, current.synchronizeExecution);
					},
					complete: function () {
						$button.removeAttr('disabled').find('.fa').removeClass('faa-flash animated');
					}
				});
			} else {
				$button = $(this);
				subscription = current.$super('subscriptions').fnGetData($button.closest('tr')[0]);
				operation = $button.attr('data-operation');
				var $popup = _('vm-execute-popup');
				var operationLabel = current.$messages['service:vm:' + operation.toLowerCase()];
				_('vm-execute-vm-name').val(subscription.data.vm.name);
				_('vm-execute-vm-id').val(subscription.data.vm.id);
				_('vm-execute-operation').val(operationLabel).next('.help-block').text(current.$messages['service:vm:' + operation.toLowerCase() + '-help']);
				$popup.find('.btn-primary').html('<i class="' + current.vmOperations[operation.toUpperCase()] + '"></i> ' + operationLabel);
				$popup.off('shown.bs.modal').on('shown.bs.modal', function () {
					$popup.find('.btn-primary').trigger('focus');
				}).off('submit.vm-execute').on('submit.vm-execute', function (e) {
					e.preventDefault();
					current.serviceVmOperation(true, $button, subscription, operation);
					$(this).modal('hide');
					return false;
				}).modal('show');
			}
		},


		/**
		 * Interval identifiers for polling
		 */
		polling: {},

		/**
		 * Stop the timer for polling
		 */
		pollStop: function (key) {
			if (current.polling[key]) {
				clearInterval(current.polling[key]);
			}
			delete current.polling[key];
		},

		/**
		 * Timer for the polling.
		 */
		pollStart: function (key, subscription, synchronizeFunction) {
			current.polling[key] = setInterval(function () {
				synchronizeFunction(key, subscription);
			}, 5000);
		},

		/**
		 * Get the new execution status.
		 */
		pollGet: function (key, subscription, url, updater, poller) {
			current.pollStop(key);
			current.polling[key] = '-';
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'service/vm/' + subscription.id + '/' + url,
				type: 'GET',
				success: function (status) {
					updater(subscription, status);
					if (status.finishedRemote) {
						return;
					}
					// Continue polling for this snapshot
					current.pollStart(key, subscription, poller);
				}
			});
		},

		/**
		 * Get the new snapshot status.
		 */
		synchronizeSnapshot: function (key, subscription) {
			current.pollGet(key, subscription, 'snapshot/task', current.updateSnapshotStatus, current.synchronizeSnapshot);
		},

		/**
		 * Get the new execution status.
		 */
		synchronizeExecution: function (key, subscription) {
			current.pollGet(key, subscription, 'execution', current.updateExecutionStatus, current.synchronizeExecution);
		},

		/**
		 * Update the execution status.
		 */
		updateExecutionStatus: function (subscription) {
			_('subscription-details-' + subscription.id).remove();
			current.$super('refreshSubscription')(subscription);
		},

		/**
		 * Update the snapshot status.
		 */
		updateSnapshotStatus: function (subscription, status) {
			if (status.finishedRemote) {
				// Stop the polling, update the buttons
				_('vm-snapshots').DataTable().ajax.reload();
				current.updateSnapshotFinalStatus(status);
			} else {
				if (!current.redrawSnapshot(status.snapshotInternalId, function (snapshot) {
					snapshot.operation = status.operation;
					snapshot.statusText = status.statusText;
				})) {
					_('vm-snapshots').DataTable().ajax.reload();
				}

				// Update the tooltip for the progress
				var statusText = 'Phase: ' + status.phase + '<br/>Started: ' + formatManager.formatDateTime(status.start) + (status.snapshotInternalId ? '<br/>Internal reference:' + status.snapshotInternalId : '');
				current.$super('$view').find('.vm-snapshot-create').attr('title', statusText);
			}
		},

		enableSnapshot: function () {
			var $button = current.$super('$view').find('.vm-snapshot-create').removeAttr('disabled').removeClass('disabled').removeClass('polling');
			$button.find('i').remove();
			return $button;
		},
		disableSnapshot: function () {
			var $button = current.$super('$view').find('.vm-snapshot-create').attr('disabled', 'disabled').addClass('disabled').addClass('polling');
			$button.find('i').remove();
			$button.find('span').first().prepend('<i class="fas fa-circle-notch fa-spin"></i> ');
			return $button;
		},

		/**
		 * Update the final (finished) status, so "status.end" is true.
		 */
		updateSnapshotFinalStatus: function (status) {
			var $button = current.enableSnapshot().find('i').remove();
			if (status.failed) {
				// Display an error
				$button.find('span').first().prepend('<i class="text-error fa fa-warning"></i>');
			}
		}
	};
	return current;
});