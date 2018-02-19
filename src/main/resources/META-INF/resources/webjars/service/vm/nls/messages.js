define({
	"root": {
		"configure-present": 'Configure (has configuration)',
		"service:vm": "Virtualization",
		"service:vm:os": "OS",
		"service:vm:network": "Network",
		"service:vm:resources": "Resources",
		"service:vm:status": "Status",
		"service:vm:powered_off": "Powered off",
		"service:vm:powered_on": "Powered on",
		"service:vm:suspended": "Suspended",
		"service:vm:off": "Power off",
		"service:vm:off-help": "A forced power off. OS may not flush data to the disks",
		"service:vm:on": "Power on",
		"service:vm:on-help": "Start or resume the VM",
		"service:vm:reset": "Reset",
		"service:vm:reset-help": "An hard reset. OS may not flush data to the disks",
		"service:vm:reboot": "Restart",
		"service:vm:reboot-help": "A clean restart where OS is involved",
		"service:vm:suspend": "Suspend",
		"service:vm:suspend-help": "Suspend the VM, correspond to an hibernate",
		"service:vm:shutdown": "Stop",
		"service:vm:shutdown-help": "A clean shutdown where OS is involved",
		"service:vm:operation": "Operation",
		"service:vm:operation-executed-help": "Effective executed operations depends on the state of the VM a execution time",
		"service:vm:operation-requested": "Requested",
		"service:vm:operation-executed": "Executed",
		"service:vm:schedule": "Schedule",
		"service:vm:cron": "CRON Expression",
		"service:vm:schedule-next": "Next execution",
		"service:vm:busy": "Busy",
		"service:vm:deployed": "Deployed",
		"service:vm:schedule:reports": "Reports",
		"service:vm:schedule:reports:executions": "Execution history",
		"service:vm:schedule:reports:executions-help": "All executions related to this subscription",
		"service:vm:schedule:reports:schedules": "Node Schedules ({{this}})",
		"service:vm:schedule:reports:schedules-help": "All visible schedules sharing the same node this subscription",
		"service:vm:nb-schedules": "Number of schedules for this VM",
		"service:vm:snapshot": "Snapshot",
		"service:vm:snapshot-create": "Snapshot <span class=\"caret\"/>",
		"service:vm:snapshot-create-no-stop": "Snapshot",
		"service:vm:snapshot-create-no-stop-help": "Snapshot is taken without stopping the VM. Data integrity may not be guaranteed",
		"service:vm:snapshot-create-stop": "Snapshot (stop VM)",
		"service:vm:snapshot-create-stop-help": "VM will be stopped, then a snapshot is created. Data integrity is guaranteed",
		"service:vm:snapshot-restore": "Restore",
		"service:vm:snapshot-delete": "Delete this snapshot",
		"service:vm:snapshot-volumes": "Volumes",
		"service:vm:snapshot-volumes-help": "Snapshot volumes attached to this VM snapshot",
		"service:vm:snapshot-id-help": "Snapshot identifier inside the node",
		"service:vm:snapshot-date": "Date",
		"service:vm:snapshot-date-help": "Snapshot creation date",
		"service:vm:snapshot-status": "Status",
		"service:vm:snapshot-status-available": "Available",
		"service:vm:snapshot-status-deleting": "Deleting",
		"service:vm:snapshot-status-not-finished-remote": "Pending operation on IaaS",
		"service:vm:snapshot-status-help": "Snapshot status progress",
		"vm-operation-success": "Requesting operation {{[1]}} done on VM {{[0]}}",
		"error": {
			"vm-cron": "Invalid CRON expression",
			"vm-cron-second": "Valid CRON expression, but cannot be every second"
		}
	},
	"fr": true
});