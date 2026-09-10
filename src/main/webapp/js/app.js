var pollTimer = null;
var lastHeaderVersion = '';

$(function(){
	loadHeaderInfo();
	loadQueue();

	// Version/date moved behind an (i) button instead of always showing in the header (2026-09-05).
	$('#info-btn').on('click', function(){
		$('#info-body').text(lastHeaderVersion || 'Unknown');
		bootstrap.Modal.getOrCreateInstance(document.getElementById('info-modal')).show();
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

	// "Send Command" - operator types anything, editable, nothing pre-sent on open. The shortcut
	// row (common commands) only makes sense here, not for Calibrate CSW's locked-in command.
	$('#send-command-btn').on('click', function(){
		$('#send-command-title').text('Send Command');
		$('#calibrate-csw-reminder').hide();
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
		$('#send-command-shortcuts').hide();
		$('#send-command-text').val('CalibrateCSWReference').prop('readonly', true);
		$('#send-command-body').html('<div class="pd-empty-message">Talking to the device...</div>');
		bootstrap.Modal.getOrCreateInstance(document.getElementById('send-command-modal')).show();
		sendCommand('CalibrateCSWReference');
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

	// Copy the command response text (the "black area") to the clipboard, so an operator can
	// paste it into email/Slack. navigator.clipboard needs a secure context, and this app is
	// served over plain HTTP, so fall back to a hidden-textarea + execCommand('copy') - and if
	// that ALSO fails (common and silent on mobile browsers), fall back one more level to a
	// prompt() the operator can manually select-all + copy from, rather than lying that it worked.
	$('#copy-command-response-btn').on('click', function(){
		var text = $('#send-command-body').text();
		var btn = $(this);
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
	});

	$(document).on('click', '.pd-tile', function(){
		if($(this).data('status') === 'Running') return; // already in progress, ignore taps
		var manifestFile = $(this).data('manifest');
		var deviceName = $(this).find('.pd-tile-name').text();
		var definitionLabel = $(this).find('.pd-tile-definition').text();
		startDeploy(manifestFile, deviceName, definitionLabel);
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
			$('#header-ips').html(html);
			lastHeaderVersion = 'Version ' + data.version;
			// Confirm only makes sense once this Paula can actually reach the factory NUC - wlan1
			// is the factory-network client interface (wlan0 is the phone-facing field hotspot),
			// see Constants.FACTORY_BASE_URL / provision-pi.sh's field WiFi design.
			$('#confirm-upgrades-btn').toggle(hasWlan1);
		}
	});
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

		html += '<button type="button" class="pd-tile ' + statusClass + '" data-manifest="' + escapeHtml(tile.manifestFile) + '" data-status="' + tile.tileStatus + '">';
		html += '<span class="pd-tile-name">' + escapeHtml(tile.productName) + '</span>';
		html += '<span class="pd-tile-definition">' + escapeHtml(tile.productDefinitionLabel) + '</span>';
		if(badge) html += '<span class="pd-tile-status-badge">' + badge + '</span>';
		html += '</button>';
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

function renderInspect(data){
	var rows = [
		['Name', data.name],
		['Device Name', data.deviceName],
		['Short Name', data.deviceShortName],
		['Serial Number', data.serialNumber],
		['Firmware', data.firmware],
		['Power Source', data.powerSource],
		['Battery', data.battery],
		['PCBs', data.pcbs],
		['Commissioned', data.commissiondate],
		['SSID', data.ssid],
		['Soft AP SSID', data.softApSsid],
		['Host Name', data.hostName],
		['Station Mode', data.stationMode],
		['Current Time', data.currenttime]
	];
	var html = '<table class="table table-sm">';
	$.each(rows, function(i, row){
		html += '<tr><th>' + escapeHtml(row[0]) + '</th><td>' + escapeHtml(row[1]) + '</td></tr>';
	});
	html += '</table>';
	$('#inspect-body').html(html);
}

// Shared by "Send Command" and "Calibrate CSW" - see SendCommandProcessingHandler.
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
