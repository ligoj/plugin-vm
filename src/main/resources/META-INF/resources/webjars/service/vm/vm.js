define(function () {
	var current = {

		/**
		 * VM table
		 */
		table: null,

		/**
		 * Current configuration.
		 */
		model: null,

		/**
		 * Managed status.
		 */
		vmStatus: {
			powered_on: 'fa fa-play text-success',
			powered_off: 'fa fa-stop text-danger',
			suspended: 'fa fa-pause text-warning'
		},

		/**
		 * Managed operations.
		 */
		vmOperations: {
			OFF: 'fa fa-power-off',
			ON: 'fa fa-play',
			SUSPEND: 'fa fa-pause',
			SHUTDOWN: 'fa fa-stop',
			RESET: 'fa fa-repeat',
			REBOOT: 'fa fa-refresh'
		},

		initialize: function () {
			current.$super('$view').off('click.service-vm-operation').on('click.service-vm-operation', '.subscriptions .service-vm-operation', current.serviceVmOperation);
		},

		/**
		 * Synchronize the VM configuration UI with the retrieved data
		 */
		configure: function (configuration) {
			current.model = configuration;
			_('vm-name').text(configuration.parameters['service:vm:vcloud:organization'] + ' / ' + configuration.parameters['service:vm:vcloud:id']);
			require(['later/later.mod', 'pretty-cron/pretty-cron'], function (later) {
				current.initializeVmConfiguration(later);
				current.later = later;
				later.date.localTime();
				_('subscribe-configuration-vm').removeClass('hide');
			});
		},

		initializeTable: function () {
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
					render: function (_i, _j, data) {
						return prettyCron.toString(data.cron, true);
					}
				}, {
					data: null,
					className: 'vm-schedules-next responsive-datetime',
					render: function (_i, _j, data) {
						return moment(later.schedule(later.parse.cron(data.cron, true)).next(1)).format(formatManager.messages.shortdateMomentJs + ' HH:mm:ss');
					}
				}, {
					data: null,
					orderable: false,
					width: '40px',
					render: function () {
						var result = '<a class="update" data-toggle="modal" data-target="#vm-schedules-popup"><i class="fa fa-pencil" data-toggle="tooltip" title="';
						result += current.$messages.update;
						result += '"></i></a> <a class="delete"><i class="fa fa-trash" data-toggle="tooltip" title="' + current.$messages['delete'] + '"></i></a>';
						return result;
					}
				}],
				destroy: true,
				buttons: [{
					extend: 'popup',
					target: '#vm-schedules-popup',
					className: 'btn-success btn-raised'
				}]
			});
		},

		getNextExecution: function (cron) {
			return cron ? moment(later.schedule(later.parse.cron(cron, true)).next(1))
				.format(formatManager.messages.shortdateMomentJs + ' HH:mm:ss') : '';
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
		initializeVmConfiguration: function (later) {
			current.initializeTable();

			var operations = [];
			for (var operation in current.vmOperations) {
				if (current.vmOperations.hasOwnProperty(operation)) {
					operations.push({
						id: operation,
						text: current.formatOperation(operation)
					});
				}
			}

			_('operation').select2({
				formatSelection: current.formatOs,
				formatResult: current.formatOs,
				escapeMarkup: function (m) {
					return m;
				},
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
				var operation = schedule.operation;
				_('operation').select2('data', operation ? {
					id: operation.toUpperCase(),
					text: current.formatOperation(operation)
				} : null);
				_('cron').val(schedule.cron || '').trigger('change');
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
			}).on('shown.bs.modal', function (event) {
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
				var $tr = $(this).closest('tr');
				current.deleteSchedule(current.table.fnGetData($tr[0]));
			});
		},

		formToObject: function () {
			var result = {
				id: current.currentId,
				cron: _('cron').val(),
				operation: _('operation').val().toLowerCase(),
				subscription: current.model.subscription
			};

			return result;
		},

		/**
		 * Render VM operations and scheduler.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			var operation;

			// Operation menu
			result += '<div class="btn-group btn-link feature dropdown" data-container="body" data-toggle="tooltip" title="' +
				current.$messages['service:vm:operation'] + '"><i class="fa fa-power-off dropdown-toggle" data-toggle="dropdown"></i>' +
				'<ul class="dropdown-menu dropdown-menu-right">';
			// Add Off,On,Shutdown,Reset,Reboot,Suspend
			for (operation in current.vmOperations) {
				if (current.vmOperations.hasOwnProperty(operation)) {
					result += '<li>' + current.renderServiceServiceVmOperationButton(current.vmOperations[operation], operation) + '</li>';
				}
			}
			result += '</ul></div>';

			// Schedule menu
			result += '<div class="btn-group btn-link feature dropdown" data-container="body" data-toggle="tooltip" title="' +
				current.$messages['service:vm:schedule'] + '"><i class="fa fa-calendar dropdown-toggle" data-toggle="dropdown"></i>' +
				'<span class="service-vm-schedule-check"><i class="fa fa-circle-o text-danger hidden"></i></span>' +
				'<ul class="dropdown-menu dropdown-menu-right">';

			// Add scheduler configuration
			result += '<li>' + current.$super('renderServicelink')('calendar menu-icon', '#/home/project/' + subscription.project + '/subscription/' + subscription.id, null, 'service:vm:schedule', null, 'service-vm-schedule-check-link') +
				'</li>';

			// Add history download
			result += '<li>' + current.$super('renderServicelink')('history menu-icon', REST_PATH + 'service/vm/' + subscription.id + '/history-' + subscription.id + '.csv', null, 'service:vm:history', ' download') + '</li>';
			result += '</ul></div>';

			return result;
		},

		renderServiceServiceVmOperationButton: function (icon, operation) {
			return '<a class="feature service-vm-operation service-vm-operation-' + operation.toLowerCase() +
				'" data-operation="' + operation + '" data-toggle="tooltip" title="' + current.$messages['service:vm:' +
					operation.toLowerCase() + '-help'] + '"><i class="fa-fw menu-icon ' + icon + '"></i> ' + current.$messages['service:vm:' +
					operation.toLowerCase()] + '</a>';
		},

		/**
		 * Display the status of the VM
		 */
		renderDetailsFeatures: function (subscription, $tr, $td) {
			var status = subscription.data.vm.status.toLowerCase();
			var busy = subscription.data.vm.busy;
			var deployed = status === 'powered_off' && subscription.data.vm.deployed;
			if (subscription.data.schedules) {
				// At least on schedule
				$td.find('.service-vm-schedule-check i').removeClass('hidden');
				var $link = $td.find('.service-vm-schedule-check-link');
				if ($link.has('.service-vm-schedule-count').length === 0) {
					$link.append('<span class="label label-success service-vm-schedule-count pull-right" data-toggle="tooltip" title="' + current.$messages['service:vm:nb-schedules'] + '"></span>');
				}
				$link.find('.service-vm-schedule-count').text(subscription.data.schedules);
			} else {
				$td.find('.service-vm-schedule-check i').addClass('hidden');
			}
			$td.find('.service-vm-schedule-count').text(subscription.data.schedules || '');
			return '<i data-toggle="tooltip" data-html="true" title="' + (current.$messages['service:vm:' + status] || status) +
				(busy ? ' (' + current.$messages['service:vm:busy'] + ')' : '') +
				(deployed ? '<br>[' + current.$messages['service:vm:deployed'] + ']' : '') + '" class="' +
				(current.vmStatus[status] || 'fa fa-question-circle-o text-muted') +
				(busy ? ' faa-flash animated' : '') + (deployed ? ' deployed' : '') + ' fa-fw service-vm-status"></i>';
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
				result.push('<i class="fa fa-' + (networkTypeToIcon[network.type] || 'slash') + '"></i> ' + network.ip + (network.dns ? ' [' + network.dns + ']' : ''));
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
				url: REST_PATH + 'service/vm/' + schedule.id,
				dataType: 'json',
				contentType: 'application/json',
				success: function () {
					notifyManager.notify((Handlebars.compile(current.$messages.deleted))(subscription + ' : ' + schedule.operation.toUpperCase()));
					current.reload();
				}
			});
		},

		/**
		 * Save or update a schedule.
		 * @param {Object} schedule to update/save : operation+CRON
		 */
		saveSchedule: function (schedule) {
			$.ajax({
				type: schedule.id ? 'PUT' : 'POST',
				url: REST_PATH + 'service/vm',
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
		 * Reload the model
		 */
		reload: function () {
			// Clear the table
			var $schedules = _('vm-schedules').DataTable();
			$schedules.clear().draw();
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'subscription/' + current.model.subscription + '/configuration',
				type: 'GET',
				success: function (data) {
					current.model = data;
					$schedules.rows.add(current.model.configuration.schedules).draw();
				}
			});
		},

		/**
		 * Execute a VM operation.
		 */
		serviceVmOperation: function () {
			var subscription = current.$super('subscriptions').fnGetData($(this).closest('tr')[0]);
			var operation = $(this).attr('data-operation');
			var vm = subscription.parameters['service:vm:vcloud:id'];
			var id = subscription.id;
			var $button = $(this);
			$button.attr('disabled', 'disabled').find('.fa').addClass('faa-flash animated');
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'service/vm/' + id + '/' + operation,
				contentType: 'application/json',
				type: 'POST',
				success: function () {
					notifyManager.notify(Handlebars.compile(current.$messages['vm-operation-success'])([vm, operation]));
				},
				complete: function () {
					$button.removeAttr('disabled').find('.fa').removeClass('faa-flash animated');
				}
			});
		}

	};
	return current;
});