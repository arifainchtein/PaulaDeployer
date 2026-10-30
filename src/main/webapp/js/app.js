var pollTimer = null;
var lastHeaderVersion = '';
var lastHeaderIpsHtml = '';
// Header clock: Pi time minus phone time, so the clock ticks locally between header refreshes
// and shows the PI's time (what Set Time sends), not the phone's.
var piClockOffsetMillis = null, piTimeZone = 'Australia/Melbourne', piNtpSynchronized = null;

$(function(){
	loadHeaderInfo();
	loadQueue();
	setInterval(renderHeaderTime, 1000);

	// Sets the Pi's clock from the internet (NTP, falling back to an HTTP Date header).
	$('#internet-time-btn').on('click', function(){
		var btn = $(this);
		btn.prop('disabled', true).text('Getting time...');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SyncInternetTime"},
			success: function(raw){
				var result = JSON.parse(raw);
				btn.prop('disabled', false).text('Set Pi Time');
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					updatePiClock(data);
					alert('Pi time set from the internet (' + data.method + ').');
				}else{
					alert(result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false).text('Set Pi Time');
				alert('Request failed.');
			}
		});
	});

	// Sets the plugged-in device's RTC to the Pi's time (devices' zone, daylight saving included).
	$('#set-time-btn').on('click', function(){
		var question = 'Set the device clock to ' + formatPiTime() + '?';
		if(piNtpSynchronized === false){
			question = 'The Pi clock has NOT been synced from the internet - it may be wrong. Press Set Pi Time first if you can.\n\n' + question;
		}
		if(!confirm(question)) return;
		var btn = $(this);
		btn.prop('disabled', true).text('Setting...');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SetDeviceTime"},
			success: function(raw){
				var result = JSON.parse(raw);
				btn.prop('disabled', false).text('Set Device Time');
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					alert((data.ok ? 'Device time set: ' : 'Sent, but the device did not answer Ok-SetTime: ') + data.command + '\n\n' + data.response);
				}else{
					alert('Could not set the time: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false).text('Set Device Time');
				alert('Request failed.');
			}
		});
	});

	// "Send Identity Packet" - resends the device's own product definition unchanged, which makes
	// it send a DeviceIdentity packet over LoRa after its next VitalSigns (see
	// SendIdentityPacketProcessingHandler).
	$('#send-identity-btn').on('click', function(){
		var btn = $(this);
		btn.prop('disabled', true).text('Sending...');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendIdentityPacket"},
			success: function(raw){
				var result = JSON.parse(raw);
				btn.prop('disabled', false).text('Send Identity Packet');
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					if(data.immediate){
						alert(data.ok ? 'Identity packet sent now over LoRa.\n\n' + data.response
							: 'The device could not send it:\n\n' + data.response);
					}else if(data.ok){
						alert('This firmware cannot send it immediately (needs Daffodil v54+), so it was queued for ' + (data.name || 'the device') + ' (' + (data.firmware || 'no firmware label') + ').\n\n'
							+ 'The device sends it right after its next Vital Signs - within about 10 minutes while it stays awake.');
					}else{
						alert('The device did not answer Ok-SetProductDefinition:\n\n' + data.response);
					}
				}else{
					alert('Could not send: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false).text('Send Identity Packet');
				alert('Request failed.');
			}
		});
	});

	// Version/date moved behind an (i) button instead of always showing in the header (2026-09-05).
	$('#info-btn').on('click', function(){
		$('#info-ips').html(lastHeaderIpsHtml || 'No wlan interfaces found');
		$('#info-body').text(lastHeaderVersion || 'Unknown');
		loadWifi({});
		bootstrap.Modal.getOrCreateInstance(document.getElementById('info-modal')).show();
	});

	// Wi-Fi panel: pin wlan1 to one saved network, or back to Automatic (WifiNetworkProcessingHandler).
	$('#info-wifi').on('click', '.pd-wifi-select-btn', function(){
		var ssid = $(this).data('ssid');
		if(!confirm('Switch Paula to ' + ssid + '? If your phone reaches Paula over Wi-Fi other than Paula\'s own hotspot, this page may lose Paula for a moment.')) return;
		loadWifi({action: 'select', id: $(this).data('id')}, true);
	});
	$('#info-wifi').on('click', '.pd-wifi-auto-btn', function(){
		loadWifi({action: 'auto'}, true);
	});

	// Clean shutdown for the field - a low-battery Pi with no monitor/keyboard nearby should be
	// powered off cleanly rather than just having its power cut, to avoid corrupting the SD card.
	$('#shutdown-btn').on('click', function(){
		if(!confirm('Shut down this Pi now? PaulaDeployer will be unreachable until it is powered back on.')) return;
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "Shutdown"},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					alert('Shutting down - this Pi will power off in a few seconds.');
				}else{
					alert('Could not shut down: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				alert('Request failed - the Pi may already be shutting down.');
			}
		});
	});

	// Restart Tomcat - needed after every WAR redeploy (jSerialComm can only load once per JVM, so the
	// serial port is dead until the process restarts). The server schedules the restart 2s out, then
	// we poll until it answers again and reload the page.
	$('#restart-tomcat-btn').on('click', function(){
		if(!confirm('Restart Tomcat now? PaulaDeployer will be unavailable for about 30 seconds.')) return;
		var btn = $(this);
		btn.prop('disabled', true);
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "RestartTomcat"},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					$('#tile-grid').html('<div class="pd-empty-message">Restarting Tomcat - the page will reload when it is back...</div>');
					waitForTomcatThenReload(Date.now());
				}else{
					btn.prop('disabled', false);
					alert('Could not restart Tomcat: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false);
				alert('Request failed - Tomcat may already be restarting.');
			}
		});
	});

	function waitForTomcatThenReload(startedAt){
		// give it time to actually go down before the first probe, so we don't hit the old process
		setTimeout(function probe(){
			$.ajax({
				type: "POST",
				url: "PaulaDeployerServlet",
				data: {formName: "GetHeaderInfo"},
				timeout: 4000,
				success: function(){ location.reload(); },
				error: function(){
					if(Date.now() - startedAt > 180000){
						alert('Tomcat has not come back after 3 minutes - check the Pi.');
						$('#restart-tomcat-btn').prop('disabled', false);
						return;
					}
					setTimeout(probe, 3000);
				}
			});
		}, 8000);
	}

	// Only shown once wlan1 (the factory-network client) has a real IP - see loadHeaderInfo.
	$('#confirm-upgrades-btn').on('click', function(){
		var btn = $(this);
		btn.prop('disabled', true).text('Confirming...');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "ConfirmUpgrades"},
			success: function(raw){
				var result = JSON.parse(raw);
				btn.prop('disabled', false).text('Confirm');
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					var message = 'Confirmed ' + data.confirmedCount + ' device(s) with the factory server.';
					if(data.errors && data.errors.length > 0){
						message += '\n\nCould not confirm:\n' + data.errors.join('\n');
					}
					alert(message);
					loadQueue(); // a just-confirmed tile becomes eligible for the Remove button
				}else{
					alert('Could not confirm upgrades: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false).text('Confirm');
				alert('Request failed - is this Pi connected to the factory network?');
			}
		});
	});

	// "Remove" - to the left of Confirm. Deletes the manifest+zip for every already-confirmed
	// (reported) successful deploy package, which also makes its tile disappear from the queue.
	// Purely local file cleanup, no network needed - independent of wlan1/Confirm's visibility.
	$('#remove-confirmed-btn').on('click', function(){
		if(!confirm('Remove all confirmed deploy packages from this Paula? This deletes their files here and cannot be undone.')) return;
		var btn = $(this);
		btn.prop('disabled', true).text('Removing...');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "RemoveConfirmed"},
			success: function(raw){
				var result = JSON.parse(raw);
				btn.prop('disabled', false).text('Remove');
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					alert('Removed ' + data.removedCount + ' confirmed device(s).');
					loadQueue();
				}else{
					alert('Could not remove: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false).text('Remove');
				alert('Request failed.');
			}
		});
	});

	$('#inspect-btn').on('click', function(){
		$('#inspect-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		var modalEl = document.getElementById('inspect-modal');
		var modal = bootstrap.Modal.getOrCreateInstance(modalEl);
		modal.show();
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "Inspect"},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					renderInspect(JSON.parse(result[DATA_KEY]));
				}else{
					$('#inspect-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
				}
			},
			error: function(){
				$('#inspect-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
			}
		});
	});

	// "Stream 2 Chat" - see StreamToChatProcessingHandler. The stream runs on the Pi, so closing
	// the window (or the phone locking) doesn't stop it; reopening shows its current state.
	$('#stream-chat-btn').on('click', function(){
		loadStreamChatParticipants();
		refreshStreamChat();
		loadStreamLogs();
		bootstrap.Modal.getOrCreateInstance(document.getElementById('stream-chat-modal')).show();
	});
	$('#stream-chat-refresh-btn').on('click', loadStreamChatParticipants);

	// "Nearby Devices" - see NearbyDevicesProcessingHandler. Polls every 5 s while open.
	$('#nearby-btn').on('click', function(){
		$('#nearby-status').text('Talking to Paula...');
		loadNearbyDevices();
		if(!nearbyTimer) nearbyTimer = setInterval(loadNearbyDevices, 5000);
		bootstrap.Modal.getOrCreateInstance(document.getElementById('nearby-modal')).show();
	});
	$('#nearby-modal').on('hidden.bs.modal', function(){
		clearInterval(nearbyTimer);
		nearbyTimer = null;
	});
	$('#nearby-check-secret, #nearby-copy-secret').on('click', function(){
		var copy = this.id === 'nearby-copy-secret';
		if(copy && !confirm('Copy the plugged-in Daffodil\'s secret into Paula?')) return;
		var $b = $(this).prop('disabled', true);
		$('#nearby-secret-status').text(copy ? 'Copying...' : 'Checking...');
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet", data: {formName: "NearbyDevices", action: copy ? 'copySecret' : 'checkSecret'},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS){
					$('#nearby-secret-status').html('<span class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</span>');
					return;
				}
				var match = JSON.parse(result[DATA_KEY]).match;
				$('#nearby-secret-status').html(match
					? '<span style="color:#2e7d32">&#10003; Paula has the same secret as the plugged-in Daffodil</span>'
					: '<span class="text-danger">&#10007; Paula\'s secret is different - press Copy secret to Paula</span>');
			},
			error: function(){ $('#nearby-secret-status').html('<span class="text-danger">Request failed.</span>'); },
			complete: function(){ $b.prop('disabled', false); }
		});
	});
	$('#nearby-clock').on('click', '#nearby-set-clock', function(){
		var $b = $(this).prop('disabled', true);
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet", data: {formName: "NearbyDevices", action: "setClock"},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert(result[DATA_KEY]);
				setTimeout(loadNearbyDevices, 1500);
			},
			error: function(){ alert('Request failed.'); $b.prop('disabled', false); }
		});
	});
	$('#nearby-list').on('click', '.nearby-wifi', function(){
		var $b = $(this).prop('disabled', true);
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet",
			data: {formName: "NearbyDevices", action: "wifi", serial: $b.data('serial'), state: $b.data('state')},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert(result[DATA_KEY]);
				loadNearbyDevices();
			},
			error: function(){ alert('Request failed.'); $b.prop('disabled', false); }
		});
	});
	$('#stream-chat-toggle-btn').on('click', function(){
		var action = streamChatRunning ? 'stop' : 'start';
		var params = {formName: "StreamToChat", action: action};
		if(action === 'start'){
			params.target = $('#stream-chat-target').val();
			params.command = $('#stream-chat-command').val();
			if(!params.target){
				alert('Choose a chat to send to.');
				return;
			}
		}
		$('#stream-chat-toggle-btn').prop('disabled', true);
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet", data: params,
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert(result[DATA_KEY]);
				refreshStreamChat();
				loadStreamLogs();
			},
			error: function(){ alert('Request failed.'); },
			complete: function(){ $('#stream-chat-toggle-btn').prop('disabled', false); }
		});
	});
	$('#stream-chat-logs').on('click', '.stream-log-delete', function(){
		var name = $(this).data('name');
		if(!confirm('Delete ' + name + '?')) return;
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet", data: {formName: "StreamToChat", action: "deleteLog", name: name},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert(result[DATA_KEY]);
				loadStreamLogs();
			}
		});
	});
	$('#stream-chat-modal').on('hidden.bs.modal', function(){
		if(streamChatTimer){ clearInterval(streamChatTimer); streamChatTimer = null; }
	});
	$('#stream-chat-modal').on('shown.bs.modal', function(){
		if(!streamChatTimer) streamChatTimer = setInterval(refreshStreamChat, 1500);
	});

	// "Send Command" - operator types anything, editable, nothing pre-sent on open. The shortcut
	// row (common commands) only makes sense here, not for Calibrate CSW's locked-in command.
	$('#send-command-btn').on('click', function(){
		$('#send-command-title').text('Send Command');
		$('#calibrate-csw-reminder').hide();
		$('#i2c-temp-reminder').hide();
		$('#send-command-shortcuts').show();
		$('#send-command-text').val('').prop('readonly', false);
		$('#send-command-body').empty();
		bootstrap.Modal.getOrCreateInstance(document.getElementById('send-command-modal')).show();
	});
	$('#send-command-go-btn').on('click', function(){
		var command = $('#send-command-text').val();
		if(!command){
			alert('Enter a command to send.');
			return;
		}
		sendCommand(command);
	});

	// Shortcut buttons above the text field - fill the field and send immediately, same as
	// typing the command and hitting Send.
	$(document).on('click', '.pd-shortcut-btn', function(){
		var command = $(this).data('command');
		$('#send-command-text').val(command);
		sendCommand(command);
	});

	// "Calibrate CSW" - fixed command, auto-sent the moment the modal opens, plus a reminder
	// that this only arms calibration (see SendCommandProcessingHandler's comment).
	$('#calibrate-csw-btn').on('click', function(){
		$('#send-command-title').text('Calibrate CSW');
		$('#calibrate-csw-reminder').show();
		$('#i2c-temp-reminder').hide();
		$('#send-command-shortcuts').hide();
		$('#send-command-text').val('CalibrateCSWReference').prop('readonly', true);
		$('#send-command-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		bootstrap.Modal.getOrCreateInstance(document.getElementById('send-command-modal')).show();
		sendCommand('CalibrateCSWReference');
	});

	// "Test I2C Temp" - fixed command, auto-sent the moment the modal opens, same pattern as
	// Calibrate CSW. One-shot (no LED sequence, no pin-sharing mode switch needed - the SHT3x
	// is on the I2C bus, independent of the flow/ultrasonic tests on pins 18/33).
	$('#test-i2c-temp-btn').on('click', function(){
		$('#send-command-title').text('Test I2C Temp');
		$('#calibrate-csw-reminder').hide();
		$('#i2c-temp-reminder').show();
		$('#send-command-shortcuts').hide();
		$('#send-command-text').val('TestI2CTemperatureSensor').prop('readonly', true);
		$('#send-command-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		bootstrap.Modal.getOrCreateInstance(document.getElementById('send-command-modal')).show();
		sendCommand('TestI2CTemperatureSensor');
	});

	// "Test Flow Sensor" - two-step workflow against Paula.ino's LED-guided flow test. Start
	// arms the device (SetTestMode#Flow then CalibrateFlowSensorXStart) - the operator then
	// watches the DEVICE's own LEDs (15s red, 5s green) to know when to catch water, not
	// anything on this screen, so Start's response is just a confirmation, not a live timer.
	// Once water is caught and measured, Calculate sends the Stop command with that volume and
	// shows the resulting qfactor.
	var selectedFlowSensor = '1';
	$(document).on('click', '.flow-sensor-select-btn', function(){
		$('.flow-sensor-select-btn').removeClass('active');
		$(this).addClass('active');
		selectedFlowSensor = $(this).data('sensor').toString();
	});
	$('#test-flow-btn').on('click', function(){
		$('#flow-test-volume-row').hide();
		$('#flow-test-volume').val('');
		$('#flow-test-body').empty();
		$('#flow-test-start-btn').prop('disabled', false).text('Start Test');
		bootstrap.Modal.getOrCreateInstance(document.getElementById('flow-test-modal')).show();
	});
	$('#flow-test-start-btn').on('click', function(){
		var btn = $(this);
		btn.prop('disabled', true).text('Starting...');
		$('#flow-test-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "SetTestMode#Flow"},
			success: function(){
				$.ajax({
					type: "POST",
					url: "PaulaDeployerServlet",
					data: {formName: "SendCommand", command: "CalibrateFlowSensor" + selectedFlowSensor + "Start"},
					success: function(raw){
						var result = JSON.parse(raw);
						if(result[STATUS_KEY] === STATUS_SUCCESS){
							var data = JSON.parse(result[DATA_KEY]);
							$('#flow-test-body').text(data.response);
							$('#flow-test-volume-row').show();
							btn.text('Test Started');
						}else{
							$('#flow-test-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
							btn.prop('disabled', false).text('Start Test');
						}
					},
					error: function(){
						$('#flow-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
						btn.prop('disabled', false).text('Start Test');
					}
				});
			},
			error: function(){
				$('#flow-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
				btn.prop('disabled', false).text('Start Test');
			}
		});
	});
	$('#flow-test-stop-btn').on('click', function(){
		var mL = $('#flow-test-volume').val();
		if(!mL || Number(mL) <= 0){
			alert('Enter the measured volume in mL.');
			return;
		}
		$('#flow-test-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "CalibrateFlowSensor" + selectedFlowSensor + "Stop#" + mL},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					$('#flow-test-body').text(data.response);
				}else{
					$('#flow-test-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
				}
			},
			error: function(){
				$('#flow-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
			}
		});
	});

	// "Test Ultrasonic" - SetTestMode#Ultrasonic switches the device's pin 18 to UART RX, then
	// GetUltrasonicReading is polled here once a second for a live distance display. Closing the
	// modal restores Flow mode (see the modal's own comment in index.html) so a later flow test
	// isn't left broken by pin 18 still being in UART mode.
	var ultrasonicPollTimer = null;
	function pollUltrasonicReading(){
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "GetUltrasonicReading"},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					$('#ultrasonic-test-body').text(data.response);
				}else{
					$('#ultrasonic-test-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
				}
			},
			error: function(){
				$('#ultrasonic-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
			}
		});
	}
	$('#test-ultrasonic-btn').on('click', function(){
		$('#ultrasonic-test-body').empty();
		$('#ultrasonic-test-start-btn').prop('disabled', false).text('Start Test').show();
		bootstrap.Modal.getOrCreateInstance(document.getElementById('ultrasonic-test-modal')).show();
	});
	$('#ultrasonic-test-start-btn').on('click', function(){
		var btn = $(this);
		btn.prop('disabled', true).text('Starting...');
		$('#ultrasonic-test-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "SetTestMode#Ultrasonic"},
			success: function(){
				btn.hide();
				pollUltrasonicReading();
				ultrasonicPollTimer = setInterval(pollUltrasonicReading, 1000);
			},
			error: function(){
				$('#ultrasonic-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
				btn.prop('disabled', false).text('Start Test');
			}
		});
	});
	document.getElementById('ultrasonic-test-modal').addEventListener('hidden.bs.modal', function(){
		if(ultrasonicPollTimer){
			clearInterval(ultrasonicPollTimer);
			ultrasonicPollTimer = null;
			$.ajax({type: "POST", url: "PaulaDeployerServlet", data: {formName: "SendCommand", command: "SetTestMode#Flow"}});
		}
	});

	// "Test Tank Pressure" - one-shot, same sensor-select pattern as "Test Flow Sensor" but no
	// Start/Stop workflow needed (no LED sequence, no SetTestMode - the ADS1115 read is
	// independent of the flow/ultrasonic pin-sharing mode switch, gated only by the physical
	// analog/digital jumper, which is what the reminder banner is for).
	var selectedPressureSensor = '1';
	$(document).on('click', '.pressure-sensor-select-btn', function(){
		$('.pressure-sensor-select-btn').removeClass('active');
		$(this).addClass('active');
		selectedPressureSensor = $(this).data('sensor').toString();
	});
	$('#test-pressure-btn').on('click', function(){
		$('#pressure-test-body').empty();
		bootstrap.Modal.getOrCreateInstance(document.getElementById('pressure-test-modal')).show();
	});
	$('#pressure-test-run-btn').on('click', function(){
		$('#pressure-test-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "TestTankPressureSensor" + selectedPressureSensor},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					$('#pressure-test-body').text(data.response);
				}else{
					$('#pressure-test-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
				}
			},
			error: function(){
				$('#pressure-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
			}
		});
	});

	// "Test Soil Sensor" - one-shot ReadSoilSensor#<address>, like Test Tank Pressure. The address
	// is validated here as well as on the device so a typo doesn't cost a serial round trip.
	$('#test-soil-btn').on('click', function(){
		$('#soil-test-body').empty();
		bootstrap.Modal.getOrCreateInstance(document.getElementById('soil-test-modal')).show();
	});
	$('#soil-test-run-btn').on('click', function(){
		var address = parseInt($('#soil-test-address').val(), 10);
		if(isNaN(address) || address < 0 || address > 247){
			$('#soil-test-body').html('<div class="text-danger">Sensor address must be a number from 0 to 247.</div>');
			return;
		}
		$('#soil-test-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "SendCommand", command: "ReadSoilSensor#" + address},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					var data = JSON.parse(result[DATA_KEY]);
					$('#soil-test-body').text(data.response);
				}else{
					$('#soil-test-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
				}
			},
			error: function(){
				$('#soil-test-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
			}
		});
	});

	// Copy the command response text (the "black area") to the clipboard, so an operator can
	// paste it into email/Slack. navigator.clipboard needs a secure context, and this app is
	// served over plain HTTP, so fall back to a hidden-textarea + execCommand('copy') - and if
	// that ALSO fails (common and silent on mobile browsers), fall back one more level to a
	// prompt() the operator can manually select-all + copy from, rather than lying that it worked.
	$('#copy-command-response-btn').on('click', function(){
		copyTextToClipboard($('#send-command-body').text(), $(this));
	});

	// Same for the flash terminal (black screen) - the full esptool/deploy log.
	$('#copy-terminal-btn').on('click', function(){
		copyTextToClipboard($('#terminal-output').text(), $(this));
	});

	$(document).on('click', '.pd-tile', function(){
		if($(this).data('status') === 'Running') return; // already in progress, ignore taps
		var manifestFile = $(this).data('manifest');
		var deviceName = $(this).find('.pd-tile-name').text();
		var definitionLabel = $(this).find('.pd-tile-definition').text();
		startDeploy(manifestFile, deviceName, definitionLabel);
	});

	// 2026-10-30: Cancel a running deployment - see CancelDeployProcessingHandler
	$(document).on('click', '#cancel-deploy-btn, .pd-tile-cancel', function(e){
		e.stopPropagation();
		var btn = $(this);
		var name = btn.data('name') || $('#terminal-title').text();
		if(!confirm('Cancel the deployment for ' + name + '? It is marked Failed; you can then retry or remove it. A flash already writing finishes by itself - check the device with Inspect afterwards.')) return;
		btn.prop('disabled', true);
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "CancelDeploy", attemptId: btn.data('attempt')},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert('Could not cancel: ' + result[DATA_KEY]);
				if(btn.hasClass('pd-tile-cancel')) loadQueue();
			},
			error: function(){
				btn.prop('disabled', false);
				alert('Request failed.');
			}
		});
	});

	$(document).on('click', '.pd-tile-remove:not(.pd-tile-cancel)', function(){
		var btn = $(this);
		if(!confirm('Remove the failed deployment for ' + btn.data('name') + ' from this Paula? This deletes its files here - send the deploy package again from the factory webapp to retry.')) return;
		btn.prop('disabled', true);
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "RemoveDeployPackage", manifestFile: btn.data('manifest')},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] === STATUS_SUCCESS){
					loadQueue();
				}else{
					btn.prop('disabled', false);
					alert('Could not remove: ' + result[DATA_KEY]);
				}
			},
			error: function(){
				btn.prop('disabled', false);
				alert('Request failed.');
			}
		});
	});

	// "Lora Sniffer" - see LoraSnifferProcessingHandler. Polls once a second while the screen is open.
	$('#lora-sniffer-btn').on('click', function(){
		$('#main-screen').hide();
		$('#sniffer-screen').show();
		$('#sniffer-output').text('');
		snifferSequence = 0;
		snifferCount = 0;
		snifferSummary = {};
		renderSnifferSummary();
		$('#sniffer-capture').text('');
		setSniffing(true, true);
	});
	$('#sniffer-toggle-btn').on('click', function(){
		setSniffing(!snifferTimer, false);
	});
	$('#sniffer-copy-btn').on('click', function(){
		copyTextToClipboard($('#sniffer-output').text() + '\n' + snifferSummaryText(), $(this));
	});
	$('#sniffer-return-btn').on('click', function(){
		setSniffing(false, true);  // ends the capture file, paused or not
		$('#sniffer-screen').hide();
		$('#main-screen').show();
	});

	$('#return-btn').on('click', function(){
		stopPolling();
		$('#terminal-screen').hide();
		$('#main-screen').show();
		loadQueue();
	});
});

var STATUS_KEY = "Status", STATUS_SUCCESS = "Success", DATA_KEY = "Data";

function loadHeaderInfo(){
	$.ajax({
		type: "POST",
		url: "PaulaDeployerServlet",
		data: {formName: "GetHeaderInfo"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS) return;
			var data = JSON.parse(result[DATA_KEY]);
			var html = '';
			if(data.interfaces.length === 0){
				html = '<span class="pd-ip-line">No wlan interfaces found</span>';
			}
			var hasWlan1 = false;
			$.each(data.interfaces, function(i, iface){
				html += '<span class="pd-ip-line"><span class="pd-ip-iface">' + escapeHtml(iface.interface) + '</span>' + escapeHtml(iface.ip) + '</span>';
				if(iface.interface === 'wlan1') hasWlan1 = true;
			});
			lastHeaderIpsHtml = html;  // shown in the (i) popup
			updatePiClock(data);
			lastHeaderVersion = 'Version ' + data.version;
			// Confirm only makes sense once this Paula can actually reach the factory NUC - wlan1
			// is the factory-network client interface (wlan0 is the phone-facing field hotspot),
			// see Constants.FACTORY_BASE_URL / provision-pi.sh's field WiFi design.
			$('#confirm-upgrades-btn').toggle(hasWlan1);
		}
	});
}

// Fills the (i) popup's Wi-Fi panel. After a button, refreshes every 5 s for 45 s: joining a network,
// the DHCP renew and the tunnel switch (factory-tunnel.timer, every 15 s) take a while to show.
var wifiRefreshTimer = null;
function loadWifi(params, followUp){
	if(followUp){
		clearInterval(wifiRefreshTimer);
		var refreshes = 0;
		wifiRefreshTimer = setInterval(function(){
			if(++refreshes > 9 || !$('#info-modal').hasClass('show')){ clearInterval(wifiRefreshTimer); return; }
			loadWifi({});
		}, 5000);
	}
	$.ajax({
		type: "POST",
		url: "PaulaDeployerServlet",
		data: $.extend({formName: "WifiNetwork"}, params),
		timeout: 10000,
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				$('#info-wifi').html('<span class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</span>');
				return;
			}
			renderWifi(JSON.parse(result[DATA_KEY]));
		},
		error: function(){
			$('#info-wifi').html('<span class="text-muted">Wi-Fi: no answer (Paula may be switching networks)</span>');
		}
	});
}

function renderWifi(data){
	var html = '<div class="pd-wifi-line"><span class="pd-ip-iface">Wi-Fi</span>';
	if(data.connected){
		html += escapeHtml(data.ssid);
		if(data.rssi !== undefined) html += ' <span class="pd-wifi-signal">' + data.rssi + ' dBm (' + wifiSignalWord(data.rssi) + ')</span>';
	}else{
		html += '<span class="text-muted">not connected (' + escapeHtml(data.state) + ')</span>';
	}
	html += '</div>';
	html += '<div class="pd-wifi-line"><span class="pd-ip-iface">Tunnel</span>'
		+ (data.tunnelUp ? 'Factory tunnel up (chat goes home through WireGuard)' : 'off (on the home Wi-Fi, chat goes straight to FACTORY2)') + '</div>';
	html += '<div class="pd-wifi-line"><span class="pd-ip-iface">Mode</span>'
		+ (data.selectedNetworkId < 0 ? 'Automatic' : 'pinned to one network') + '</div>';
	html += '<div class="pd-wifi-buttons">';
	$.each(data.networks, function(i, n){
		var pinned = data.selectedNetworkId === n.id;
		html += '<button type="button" class="btn btn-sm ' + (pinned ? 'btn-success' : 'btn-outline-success') + ' pd-wifi-select-btn" data-id="' + n.id + '" data-ssid="' + escapeHtml(n.ssid) + '">Use ' + escapeHtml(n.ssid) + '</button>';
	});
	html += '<button type="button" class="btn btn-sm ' + (data.selectedNetworkId < 0 ? 'btn-success' : 'btn-outline-success') + ' pd-wifi-auto-btn">Automatic</button>';
	html += '</div>';
	if(data.message) html += '<div class="pd-wifi-message">' + escapeHtml(data.message) + '</div>';
	$('#info-wifi').html(html);
}

function wifiSignalWord(rssi){
	if(rssi >= -60) return 'good';
	if(rssi >= -70) return 'fair';
	if(rssi >= -80) return 'weak';
	return 'very weak';
}

function updatePiClock(data){
	if(data.piTimeMillis === undefined) return;
	piClockOffsetMillis = data.piTimeMillis - Date.now();
	if(data.timeZone) piTimeZone = data.timeZone;
	piNtpSynchronized = (data.ntpSynchronized === undefined) ? null : data.ntpSynchronized;
	renderHeaderTime();
}

function formatPiTime(){
	if(piClockOffsetMillis === null) return '';
	// e.g. "07/10/26 12:58:31 AEDT" - short enough to keep the header on one line on a 360px phone
	return new Date(Date.now() + piClockOffsetMillis).toLocaleString('en-AU', {
		timeZone: piTimeZone, day: '2-digit', month: '2-digit', year: '2-digit',
		hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZoneName: 'short'
	}).replace(',', '');
}

function renderHeaderTime(){
	if(piClockOffsetMillis === null) return;
	var label = formatPiTime();
	if(piNtpSynchronized === false) label += ' (not synced)';
	$('#header-time').text(label).toggleClass('pd-time-unsynced', piNtpSynchronized === false);
}

function loadQueue(){
	$('#tile-grid').html('<div class="pd-empty-message">Loading deploy queue...</div>');
	$.ajax({
		type: "POST",
		url: "PaulaDeployerServlet",
		data: {formName: "GetDeployQueue"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				$('#tile-grid').html('<div class="pd-empty-message">Could not load deploy queue.</div>');
				return;
			}
			renderQueue(JSON.parse(result[DATA_KEY]).queue);
		},
		error: function(){
			$('#tile-grid').html('<div class="pd-empty-message">Could not load deploy queue.</div>');
		}
	});
}

function renderQueue(queue){
	if(queue.length === 0){
		$('#tile-grid').html('<div class="pd-empty-message">No deploy packages waiting - use "Send Deploy Package" on the factory webapp.</div>');
		$('#remove-confirmed-btn').hide();
		return;
	}
	var html = '';
	var hasConfirmed = false;
	$.each(queue, function(i, tile){
		var statusClass = 'pd-tile-pending', badge = '';
		if(tile.tileStatus === 'Running'){ statusClass = 'pd-tile-running'; badge = 'In progress...'; }
		else if(tile.tileStatus === 'Success'){ statusClass = 'pd-tile-success'; badge = 'Updated ' + formatShortDate(tile.completedOn); }
		else if(tile.tileStatus === 'Failed'){ statusClass = 'pd-tile-failed'; badge = 'Failed - tap to retry'; }
		if(tile.tileStatus === 'Success' && tile.reported) hasConfirmed = true;

		html += '<div class="pd-tile-wrap">';
		html += '<button type="button" class="pd-tile ' + statusClass + '" data-manifest="' + escapeHtml(tile.manifestFile) + '" data-status="' + tile.tileStatus + '">';
		html += '<span class="pd-tile-name">' + escapeHtml(tile.productName) + '</span>';
		html += '<span class="pd-tile-definition">' + (tile.newDeployment ? 'New: ' : '') + escapeHtml(tile.productDefinitionLabel) + '</span>';
		if(tile.newDeployment && tile.serialNumber) html += '<span class="pd-tile-serial">SN ' + escapeHtml(tile.serialNumber) + '</span>';
		if(badge) html += '<span class="pd-tile-status-badge">' + badge + '</span>';
		html += '</button>';
		// Failed tiles get their own remove "x" - the footer Remove only clears confirmed successes
		if(tile.tileStatus === 'Failed'){
			html += '<button type="button" class="pd-tile-remove" title="Remove this failed deployment" data-manifest="' + escapeHtml(tile.manifestFile) + '" data-name="' + escapeHtml(tile.productName) + '">&times;</button>';
		}
		// 2026-10-30: a Running tile can be cancelled (it used to be stuck for good if Paula restarted)
		if(tile.tileStatus === 'Running' && tile.lastAttemptId){
			html += '<button type="button" class="pd-tile-remove pd-tile-cancel" title="Cancel this deployment" data-attempt="' + tile.lastAttemptId + '" data-name="' + escapeHtml(tile.productName) + '">&times;</button>';
		}
		html += '</div>';
	});
	$('#tile-grid').html(html);
	$('#remove-confirmed-btn').toggle(hasConfirmed);
}

function startDeploy(manifestFile, deviceName, definitionLabel){
	$('#main-screen').hide();
	$('#terminal-screen').show();
	$('#terminal-title').text(deviceName + ' — ' + definitionLabel);
	$('#terminal-output').text('Starting...\n');
	$('#return-btn').hide();
	$('#terminal-status-line').remove();

	$.ajax({
		type: "POST",
		url: "PaulaDeployerServlet",
		data: {formName: "StartDeploy", manifestFile: manifestFile},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				$('#terminal-output').text('Could not start: ' + result[DATA_KEY]);
				$('#return-btn').show();
				return;
			}
			pollStatus(JSON.parse(result[DATA_KEY]).attemptId);
		},
		error: function(){
			$('#terminal-output').text('Request failed.');
			$('#return-btn').show();
		}
	});
}

function pollStatus(attemptId){
	$('#cancel-deploy-btn').data('attempt', attemptId).prop('disabled', false).show();
	pollTimer = setInterval(function(){
		$.ajax({
			type: "POST",
			url: "PaulaDeployerServlet",
			data: {formName: "GetDeployStatus", attemptId: attemptId},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) return;
				var data = JSON.parse(result[DATA_KEY]);

				var el = document.getElementById('terminal-output');
				var wasScrolledToBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 20;
				el.textContent = data.terminallog;
				if(wasScrolledToBottom) el.scrollTop = el.scrollHeight;

				if(data.status !== 'Running'){
					stopPolling();
					$('#cancel-deploy-btn').hide();
					showCompletionBanner(data.status);
					$('#return-btn').show();
				}
			}
		});
	}, 1000);
}

function stopPolling(){
	if(pollTimer){ clearInterval(pollTimer); pollTimer = null; }
}

function showCompletionBanner(status){
	var cls = status === 'Success' ? 'pd-terminal-status-success' : 'pd-terminal-status-failed';
	var text = status === 'Success' ? 'Success' : 'Failed';
	$('<div id="terminal-status-line" class="pd-terminal-status-line ' + cls + '">' + text + '</div>').insertBefore('#terminal-output');
}

// Copy text to the clipboard, so an operator can paste it into email/Slack. navigator.clipboard
// needs a secure context, and this app is served over plain HTTP, so fall back to a
// hidden-textarea + execCommand('copy') - and if that ALSO fails (common and silent on mobile
// browsers), fall back one more level to a prompt() the operator can manually select-all + copy
// from, rather than lying that it worked. btn shows "Copied!" briefly on success.
function copyTextToClipboard(text, btn){
	function flashCopied(){
		var original = btn.text();
		btn.text('Copied!');
		setTimeout(function(){ btn.text(original); }, 1200);
	}
	function fallback(){
		if(!copyViaTextarea(text)){
			window.prompt('Copy failed automatically - select all and copy manually:', text);
		}else{
			flashCopied();
		}
	}
	if(navigator.clipboard && window.isSecureContext){
		navigator.clipboard.writeText(text).then(flashCopied, fallback);
	}else{
		fallback();
	}
}

// Epoch seconds from the device (Commissioned, Current Time) as a date/time in the devices' zone.
function formatDeviceEpoch(value){
	var n = parseInt(value);
	if(!(n > 1000000000)) return value;
	return new Date(n * 1000).toLocaleString('en-AU', {
		timeZone: piTimeZone, day: '2-digit', month: '2-digit', year: 'numeric',
		hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
	}).replace(',', '');
}

function renderInspect(data){
	// Third element = already-escaped HTML (used instead of the plain value).
	var rows = [
		['Name', data.name],
		['Device Name', data.deviceName],
		['Short Name', data.deviceShortName],
		['Serial Number', data.serialNumber],
		['Firmware', data.firmware, firmwareInspectHtml(data)],
		['Running Build', '', data.runningBuild ? escapeHtml(formatBuildStamp(data.runningBuild)) : '<span class="text-muted">unknown (older firmware)</span>'],
		['Power Source', data.powerSource],
		['Battery', data.battery],
		['PCBs', data.pcbs],
		['Commissioned', formatDeviceEpoch(data.commissiondate)],
		['SSID', data.ssid],
		['Soft AP SSID', data.softApSsid],
		['Host Name', data.hostName],
		['Station Mode', data.stationMode],
		['Current Time', formatDeviceEpoch(data.currenttime)]
	];
	var html = '<table class="table table-sm">';
	$.each(rows, function(i, row){
		html += '<tr><th>' + escapeHtml(row[0]) + '</th><td>' + (row.length > 2 ? row[2] : escapeHtml(row[1])) + '</td></tr>';
	});
	html += '</table>';
	$('#inspect-body').html(html);
}

// The device saves the running build stamp along with the firmware label (SetProductDefinition),
// so a label left over from an earlier flash shows up here instead of passing as current.
// "Daffodil v55" -> the source on GitHub. DigitalStablesWatchDir tags every exported release with the
// bare version number on github.com/arifainchtein/<repo>, so v55 is /tree/55. Only used when the label
// is current - a stale label names code that isn't what's running. Opens in a new tab.
function firmwareSourceLink(firmware, labelHtml){
	var m = /^([A-Za-z0-9_.-]+) v(\d+)$/.exec((firmware || '').trim());
	if(!m) return labelHtml;
	var url = 'https://github.com/arifainchtein/' + encodeURIComponent(m[1]) + '/tree/' + m[2];
	return '<a class="pd-fw-link" href="' + url + '" target="_blank" rel="noopener">' + labelHtml + '</a>';
}

function firmwareInspectHtml(data){
	var label = escapeHtml(data.firmware || '');
	if(!data.runningBuild) return label;
	if(data.labelBuild && data.labelBuild === data.runningBuild){
		return firmwareSourceLink(data.firmware, label) + ' <span class="pd-fw-current">&#10003; current</span>';
	}
	return '<span class="pd-fw-stale">' + (label || 'no label') + ' &ndash; not current</span>' +
		'<div class="pd-fw-stale-note">Flashed without updating the label. Deploy again to fix it.</div>';
}

// YYMMDDhh -> "26100714 (07/10/26 14:00)"
function formatBuildStamp(stamp){
	var s = String(stamp);
	if(!/^\d{8}$/.test(s)) return s;
	return s + ' (' + s.substr(4, 2) + '/' + s.substr(2, 2) + '/' + s.substr(0, 2) + ' ' + s.substr(6, 2) + ':00)';
}

// Shared by "Send Command" and "Calibrate CSW" - see SendCommandProcessingHandler.
var streamChatRunning = false;
var streamChatTimer = null;

function loadStreamChatParticipants(){
	var $sel = $('#stream-chat-target');
	var previous = $sel.val();
	$sel.html('<option>Loading chats...</option>');
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "StreamToChat", action: "participants"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				streamFileOnlyOption($sel, 'chats unavailable - ' + result[DATA_KEY]);
				return;
			}
			var data = JSON.parse(result[DATA_KEY]);
			$sel.empty();
			$sel.append($('<option>').val(STREAM_FILE_ONLY).text(STREAM_FILE_ONLY + ' (no WiFi needed)'));
			data.participants.forEach(function(name){
				$sel.append($('<option>').val(name).text(name));
			});
			if(previous && data.participants.indexOf(previous) >= 0) $sel.val(previous);
		},
		error: function(){ streamFileOnlyOption($sel, 'cannot reach FactoryChat'); }
	});
}

// Must match SerialChatStreamer.FILE_ONLY.
var STREAM_FILE_ONLY = 'File only';

function streamFileOnlyOption($sel, why){
	$sel.empty().append($('<option>').val(STREAM_FILE_ONLY).text(STREAM_FILE_ONLY + ' (' + why + ')'));
}

function loadStreamLogs(){
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "StreamToChat", action: "logs"},
		success: function(raw){
			var result = JSON.parse(raw);
			var $logs = $('#stream-chat-logs');
			if(result[STATUS_KEY] !== STATUS_SUCCESS){ $logs.text(result[DATA_KEY]); return; }
			var logs = JSON.parse(result[DATA_KEY]);
			if(!logs.length){ $logs.html('<span class="text-muted">None yet.</span>'); return; }
			$logs.empty();
			logs.forEach(function(l){
				var kb = l.size < 1024 ? l.size + ' B' : Math.round(l.size / 1024) + ' KB';
				$logs.append($('<div class="d-flex align-items-center" style="gap:8px;padding:3px 0;border-bottom:1px solid #eee;">')
					.append($('<a style="flex:1;word-break:break-all;" download>').attr('href', 'streamlogs/' + encodeURIComponent(l.name)).text(l.name))
					.append($('<span class="text-muted" style="white-space:nowrap;">').text(kb))
					.append($('<button type="button" class="btn btn-outline-danger btn-sm stream-log-delete">').attr('data-name', l.name).html('&times;')));
			});
		}
	});
}

function refreshStreamChat(){
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "StreamToChat", action: "status"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS) return;
			var s = JSON.parse(result[DATA_KEY]);
			streamChatRunning = s.running;
			$('#stream-chat-toggle-btn').text(s.running ? 'Stop' : 'Start')
				.toggleClass('btn-success', !s.running).toggleClass('btn-danger', s.running);
			$('#stream-chat-target, #stream-chat-command').prop('disabled', s.running);
			var where = s.chat ? '<strong>' + escapeHtml(s.target) + '</strong> and ' : '';
			var fileTxt = s.file ? 'file <strong>' + escapeHtml(s.file) + '</strong>' : 'a file';
			var status = s.running
				? 'Streaming to ' + where + fileTxt + ' from ' + escapeHtml(s.port) + ': ' + s.linesRead + ' lines' + (s.chat ? ', ' + s.messagesSent + ' messages' : '')
				: (s.linesRead > 0 ? 'Stopped after ' + s.linesRead + ' lines, saved as ' + escapeHtml(s.file || '-') : 'Not streaming');
			if(s.lastError) status += '<div class="text-danger">' + escapeHtml(s.lastError) + '</div>';
			$('#stream-chat-status').html(status);
			var $body = $('#stream-chat-body');
			var atBottom = $body[0].scrollHeight - $body.scrollTop() - $body.outerHeight() < 30;
			$body.text((s.lines || []).join('\n'));
			if(atBottom) $body.scrollTop($body[0].scrollHeight);
		}
	});
}

function sendCommand(command){
	$.ajax({
		type: "POST",
		url: "PaulaDeployerServlet",
		data: {formName: "SendCommand", command: command},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] === STATUS_SUCCESS){
				var data = JSON.parse(result[DATA_KEY]);
				$('#send-command-body').text(data.response);
			}else{
				$('#send-command-body').html('<div class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</div>');
			}
		},
		error: function(){
			$('#send-command-body').html('<div class="text-danger">Request failed - is the device plugged in?</div>');
		}
	});
}

// Returns true/false - callers must check, since execCommand('copy') fails silently (especially
// on mobile) rather than throwing. Kept on-screen (opacity 0, not off-screen) and with an explicit
// setSelectionRange - iOS Safari won't select() a textarea it considers off-screen/hidden.
// Appended INSIDE the open Bootstrap modal (not document.body): Bootstrap 5's modal focus trap
// watches focusin on the whole document and yanks focus back inside the modal the instant it
// moves to an element outside it, which silently stole focus away from a body-level textarea
// before select()/execCommand('copy') could act on it - copy always "succeeded" but grabbed
// nothing.
function copyViaTextarea(text){
	var openModal = document.querySelector('.modal.show') || document.body;
	var ta = document.createElement('textarea');
	ta.value = text;
	ta.style.position = 'fixed';
	ta.style.top = '0';
	ta.style.left = '0';
	ta.style.opacity = '0';
	ta.style.fontSize = '16px';
	ta.contentEditable = true;
	ta.readOnly = false;
	openModal.appendChild(ta);
	ta.focus();
	ta.select();
	ta.setSelectionRange(0, text.length);
	var ok = false;
	try { ok = document.execCommand('copy'); } catch(e) { ok = false; }
	openModal.removeChild(ta);
	return ok;
}

function formatShortDate(epochMillis){
	if(!epochMillis) return '';
	return new Date(epochMillis).toLocaleDateString(undefined, {month: 'short', day: 'numeric'});
}

function escapeHtml(s){
	if(s === undefined || s === null) return '';
	return String(s).replace(/[&<>"']/g, function(c){
		return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
	});
}

// ---------------------------------------------------------------- Nearby devices (2026-10-14)

var nearbyTimer = null;
var NEARBY_WIFI = {1: 'own hotspot', 2: 'router, no internet', 3: 'router + internet'};

function nearbyAgo(sec){
	if(sec < 60) return sec + ' s ago';
	if(sec < 3600) return Math.floor(sec / 60) + ' min ago';
	return Math.floor(sec / 3600) + ' h ' + Math.floor((sec % 3600) / 60) + ' min ago';
}

function loadNearbyDevices(){
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "NearbyDevices", action: "list"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				$('#nearby-status').html('<span class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</span>');
				return;
			}
			var listing = JSON.parse(result[DATA_KEY]);
			var devices = listing.devices || [];
			showNearbyClock(listing);
			$('#nearby-status').text(devices.length ? '' : 'None heard yet - waiting for a device to send.');
			$('#nearby-list').html(devices.map(nearbyDeviceHtml).join(''));
		},
		error: function(){ $('#nearby-status').html('<span class="text-danger">Request failed.</span>'); }
	});
}

function nearbyDeviceHtml(d){
	var on = d.wifi > 0, c = d.cmd, pending = c && c.state === 1;
	var wifi = on ? '<span style="color:#2e7d32">&#10003; WiFi on</span> - ' + escapeHtml(NEARBY_WIFI[d.wifi] || ('status ' + d.wifi))
		: '<span class="text-danger">&#10007; WiFi off</span>';
	var note = '';
	if(pending) note = '<div style="color:#ef6c00">Turning ' + (c.on ? 'on' : 'off') + '... sent ' + c.sends + 'x, ' + nearbyAgo(c.ageSec)
		+ (d.sleep > 0 ? ' - asleep, answers when it wakes (every ' + d.sleep + ' s)' : '') + '</div>';
	else if(c && c.state === 2) note = '<div class="text-muted">Turned ' + (c.on ? 'on' : 'off') + ' ' + nearbyAgo(c.ageSec) + '</div>';
	else if(c && c.state === 3) note = '<div class="text-danger">Gave up after 15 min - device not heard</div>';
	var btn = pending ? '<button type="button" class="btn btn-outline-secondary btn-sm" disabled>Waiting...</button>'
		: '<button type="button" class="btn ' + (on ? 'btn-outline-secondary' : 'btn-primary') + ' btn-sm nearby-wifi" data-serial="' + escapeHtml(d.serial) + '" data-state="' + (on ? 'off' : 'on') + '">'
		  + (on ? 'Turn WiFi off' : 'Turn WiFi on') + '</button>';
	return '<div style="border-bottom:1px solid #eee;padding:8px 0;font-size:14px;">'
		+ '<div class="d-flex justify-content-between align-items-center" style="gap:8px;"><strong>' + escapeHtml(d.name || d.serial) + '</strong>' + btn + '</div>'
		+ '<div>' + wifi + ' &middot; ' + Number(d.battery).toFixed(2) + ' V &middot; ' + (d.sleep > 0 ? 'asleep ' + d.sleep + ' s' : 'awake')
		+ ' &middot; heard ' + nearbyAgo(d.ageSec) + ' (' + d.rssi + ' dBm)</div>' + note
		+ '<div class="text-muted" style="font-family:monospace;font-size:12px;">' + escapeHtml(d.serial) + '</div></div>';
}

// Paula's clock against the Pi's - Daffodils reject commands more than 15 min off.
function showNearbyClock(listing){
	if(listing.clockOffsetSec === undefined){ $('#nearby-clock').empty(); return; }
	var off = listing.clockOffsetSec, abs = Math.abs(off);
	var text = abs < 60 ? 'on time (' + off + ' s)' : (abs >= 3600 ? Math.floor(abs / 3600) + ' h ' : '') + Math.floor((abs % 3600) / 60) + ' min ' + (off > 0 ? 'fast' : 'slow');
	var color = abs < 60 ? '#2e7d32' : abs < 900 ? '#ef6c00' : '#c62828';
	var html = 'Paula\'s clock: <strong style="color:' + color + '">' + text + '</strong>'
		+ (listing.paulaClock ? ' <span class="text-muted">(Paula ' + escapeHtml(listing.paulaClock) + ', Pi ' + escapeHtml(listing.piClock) + ')</span>' : '');
	if(abs >= 900) html += ' - Daffodils will reject its commands';
	if(listing.piNtpSynced === false) html += '<div class="text-danger">The Pi\'s clock is not internet-synced - press Set Pi Time first.</div>';
	if(abs >= 60) html += ' <button type="button" class="btn btn-sm btn-warning" id="nearby-set-clock">Set Paula\'s clock</button>';
	$('#nearby-clock').html(html);
}

var snifferTimer = null, snifferSequence = 0, snifferCount = 0;
// snifferSummary: label -> {count, last} (the label - device name or kind of packet - comes from PaulaRadio.labelOf)
var snifferSummary = {};

// Start / stop: Paula is told to print its packets (LoraSniffer#1/#0), and the page polls while on.
// session: opening / leaving the screen - the Pi starts / ends the capture file (not Pause/Resume).
function setSniffing(on, session){
	if(snifferTimer){ clearInterval(snifferTimer); snifferTimer = null; }
	$('#sniffer-toggle-btn').text(on ? 'Pause' : 'Resume');
	$('#sniffer-status').text(on ? 'Talking to Paula...' : 'Paused');
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "LoraSniffer", action: on ? "start" : "stop", session: session},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){
				$('#sniffer-status').text('');
				$('#sniffer-toggle-btn').text('Resume');
				appendSnifferHtml('<span class="pd-sniffer-bad">' + escapeHtml(result[DATA_KEY]) + '</span>');
				return;
			}
			if(!on) return;
			var started = JSON.parse(result[DATA_KEY]);
			snifferSequence = started.sequence;
			showSnifferCapture(started.capture);
			showSnifferCount();
			snifferTimer = setInterval(pollSniffer, 1000);
		},
		error: function(){
			$('#sniffer-status').text('Request failed');
			$('#sniffer-toggle-btn').text('Resume');
		}
	});
}

function pollSniffer(){
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "LoraSniffer", action: "poll", after: snifferSequence},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS || !snifferTimer) return;
			var data = JSON.parse(result[DATA_KEY]);
			if(data.sequence < snifferSequence) snifferSequence = 0;  // Tomcat restarted
			data.packets.forEach(function(p){
				if(p.sequence <= snifferSequence) return;
				snifferSequence = p.sequence;
				snifferCount++;
				appendSnifferHtml(snifferPacketHtml(p));
				var entry = snifferSummary[p.label] || (snifferSummary[p.label] = {count: 0});
				entry.count++;
				entry.last = p.time;
			});
			if(data.packets.length) renderSnifferSummary();
			if(!data.connected) $('#sniffer-status').text('Paula not connected - is it plugged in?');
			else showSnifferCount();
		}
	});
}

function showSnifferCount(){
	$('#sniffer-status').text(snifferCount ? snifferCount + ' packet' + (snifferCount === 1 ? '' : 's') : 'Listening...');
}

// Where this session is being saved (FieldCaptures), linked so it can be opened from the phone.
function showSnifferCapture(capture){
	if(!capture){ $('#sniffer-capture').text('Not being saved - see the Pi\'s log'); return; }
	$('#sniffer-capture').html('Saving to <a href="captures/' + escapeHtml(capture) + '" target="_blank">captures/' + escapeHtml(capture) + '</a>'
		+ ' &middot; <a href="captures/" target="_blank">all captures</a>');
}

// 14:03:22 Cleotilde R -87 s 9.5  (R = RSSI, s = SNR, single spaces - short so a line fits a phone)
function snifferPacketHtml(p){
	if(p.raw) return escapeHtml(p.time + ' ' + p.raw);
	var rssiClass = p.rssi < -110 ? 'pd-sniffer-weak' : (p.rssi < -95 ? 'pd-sniffer-fair' : '');
	var line = escapeHtml(p.time) + ' ' + escapeHtml(p.label);
	line += ' <span class="' + rssiClass + '">R ' + p.rssi + '</span> s ' + Number(p.snr).toFixed(1);
	if(p.ok === false) line += ' <span class="pd-sniffer-bad">' + (p.type === 'WeatherForecastUpdate' ? 'BAD CODE' : 'BAD CHECKSUM') + '</span>';
	if(p.hex) line += '\n    ' + p.size + ' B: ' + escapeHtml(p.hex);
	return line;
}

// The summary as plain text, for Copy: "Cleotilde  12 received, last 14:03:22"
function snifferSummaryText(){
	var names = Object.keys(snifferSummary).sort();
	if(!names.length) return 'Summary: nothing heard yet';
	return 'Summary:\n' + names.map(function(name){
		var e = snifferSummary[name];
		return name + '  ' + e.count + ' received, last ' + e.last;
	}).join('\n');
}

function renderSnifferSummary(){
	var names = Object.keys(snifferSummary).sort();
	if(!names.length){
		$('#sniffer-summary-body').html('<tr><td colspan="3" class="text-muted">Nothing heard yet</td></tr>');
		return;
	}
	$('#sniffer-summary-body').html(names.map(function(name){
		var e = snifferSummary[name];
		return '<tr><td>' + escapeHtml(name) + '</td><td class="text-end">' + e.count + '</td><td class="text-end">' + escapeHtml(e.last) + '</td></tr>';
	}).join(''));
}

function appendSnifferHtml(html){
	var el = document.getElementById('sniffer-output');
	var wasScrolledToBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 20;
	el.insertAdjacentHTML('beforeend', html + '\n');
	if(wasScrolledToBottom) el.scrollTop = el.scrollHeight;
}
