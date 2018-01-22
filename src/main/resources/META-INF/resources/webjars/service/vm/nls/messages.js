define({
	"root": {
		"service:vm": "Virtualization",
		"service:vm:os": "OS",
		"service:vm:network": "Network",
		"service:vm:resources": "Resources",
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
		"service:vm:schedule": "Schedule",
		"service:vm:cron": "CRON Expression",
		"service:vm:schedule-next": "Next execution",
		"service:vm:busy": "Busy",
		"service:vm:deployed": "Deployed",
		"service:vm:history": "History",
		"service:vm:nb-schedules": "Number of schedules for this VM",
		"vm-operation-success": "Requesting operation {{[1]}} done on VM {{[0]}}",
		"error": {
			"vm-cron": "Invalid CRON expression",
			"vm-cron-second": "Valid CRON expression, but cannot be every second",
			"vcloud-login": "Authentication failed"
		}
	},
	"fr": true
});