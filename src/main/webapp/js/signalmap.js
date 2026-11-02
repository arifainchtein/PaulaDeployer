// ---------------------------------------------------------------- Signal Map (2026-11-03)
//
// Standing here, can a device's packets reach Annabelle? Paula listens (the Lora Sniffer - every
// packet it hears, with RSSI/SNR, and for data packets the position the device broadcasts), the
// phone's GPS stands in for Paula's position (Paula has none - that's Paula2), and everything goes
// on a satellite map:
//   - the banner on top: how well Paula hears Annabelle's weather forecast at this spot. Annabelle
//     is the gateway; hearing it well here is the best sign (not proof - its transmitter can be
//     stronger than a Daffodil's) that a device here would get through. Phase 2's ping makes it proof.
//   - one line per device, from where Paula was when it heard it to where the device is, coloured
//     by signal; it fades as the packet gets old, since you have probably walked on since.
//   - the list below: signal bars, and for Daffodils the Nearby Devices WiFi on/off (same command).
// Map tiles come over the phone's own internet (4G hotspot), not through Paula.
// Uses app.js's escapeHtml, nearbyAgo, NEARBY_WIFI, STATUS_KEY/STATUS_SUCCESS/DATA_KEY.

var SM_POLL_MS = 1000, SM_NEARBY_MS = 5000, SM_RENDER_MS = 5000;
var SM_FRESH_SEC = 180, SM_OLD_SEC = 900;            // full colour up to 3 min, faded to 15 min, then dashed
var SM_DEFAULT_CENTER = [-37.13305556, 144.47472222]; // Daffodil.ino's default position - the farm
var SM_TILES = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';

var smMap = null, smPollTimer = null, smNearbyTimer = null, smRenderTimer = null;
var smSequence = 0, smWatchId = null;
var smMe = null;          // {lat, lon, acc, manual} - the phone, i.e. Paula
var smHeard = {};         // key -> {key, name, type, serial, rssi, snr, at (ms), count, lat, lon, hereLat, hereLon}
var smSaved = {};         // key -> {lat, lon, saved} (SignalMapProcessingHandler)
var smNearby = {};        // serial -> GetNearbyDevices entry (WiFi state, battery, sleep, pending command)
var smLayers = {};        // key -> {marker, line}
// The view follows what is heard - zoomed to fit each time a device with a position turns up -
// until you drag the map yourself (then "Show all" brings it back).
var smMeMarker = null, smMeCircle = null, smUserMoved = false, smFittedCount = 0;

$(function(){
	$('#nearby-btn').on('click', openSignalMap);
	$('#map-return-btn').on('click', closeSignalMap);
	$('#map-center-btn').on('click', function(){
		if(smMe) smMap.setView([smMe.lat, smMe.lon], Math.max(smMap.getZoom(), 17));
		else alert('No position yet - see the note above the map.');
	});
	$('#map-fit-btn').on('click', function(){ fitSignalMap(true); });
	$('#map-list').on('click', '.sm-save-here', function(){
		var key = $(this).data('key');
		if(!smMe){ alert('Paula has no position yet - wait for the phone\'s GPS (or tap the map where you are).'); return; }
		if(!confirm('Save ' + key + '\'s position as where you are standing now (±' + Math.round(smMe.acc) + ' m)?')) return;
		signalMapPositions({action: 'save', label: key, lat: smMe.lat, lon: smMe.lon});
	});
	$('#map-list').on('click', '.sm-clear-saved', function(){
		var key = $(this).data('key');
		if(!confirm('Forget the saved position of ' + key + '? It goes back to the one it broadcasts, if any.')) return;
		signalMapPositions({action: 'clear', label: key});
	});
	// Same WiFi command as the Nearby Devices window (NearbyDevicesProcessingHandler)
	$('#map-list').on('click', '.sm-wifi', function(){
		var $b = $(this).prop('disabled', true);
		$.ajax({
			type: "POST", url: "PaulaDeployerServlet",
			data: {formName: "NearbyDevices", action: "wifi", serial: $b.data('serial'), state: $b.data('state')},
			success: function(raw){
				var result = JSON.parse(raw);
				if(result[STATUS_KEY] !== STATUS_SUCCESS) alert(result[DATA_KEY]);
				loadSignalMapNearby();
			},
			error: function(){ alert('Request failed.'); $b.prop('disabled', false); }
		});
	});
});

function openSignalMap(){
	$('#main-screen').hide();
	$('#map-screen').show();
	smHeard = {}; smNearby = {}; smSequence = 0; smUserMoved = false; smFittedCount = 0;
	if(!smMap) initSignalMap();
	Object.keys(smLayers).forEach(removeSignalMapLayer);
	setTimeout(function(){ smMap.invalidateSize(); }, 0);  // it was laid out while hidden
	$('#map-capture').text('');
	renderSignalMap();
	signalMapPositions({action: 'list'});
	startSignalMapLocation();
	$('#map-status').text('Talking to Paula...');
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "LoraSniffer", action: "start", session: true},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){ $('#map-status').html('<span class="text-danger">' + escapeHtml(result[DATA_KEY]) + '</span>'); return; }
			var started = JSON.parse(result[DATA_KEY]);
			smSequence = started.sequence;
			showSignalMapCapture(started.capture);
			$('#map-status').text('Listening...');
			smPollTimer = setInterval(pollSignalMap, SM_POLL_MS);
		},
		error: function(){ $('#map-status').text('Request failed'); }
	});
	loadSignalMapNearby();
	smNearbyTimer = setInterval(loadSignalMapNearby, SM_NEARBY_MS);
	smRenderTimer = setInterval(renderSignalMap, SM_RENDER_MS);  // ages and fading move on between packets
}

function closeSignalMap(){
	[smPollTimer, smNearbyTimer, smRenderTimer].forEach(function(t){ if(t) clearInterval(t); });
	smPollTimer = smNearbyTimer = smRenderTimer = null;
	if(smWatchId !== null && navigator.geolocation) navigator.geolocation.clearWatch(smWatchId);
	smWatchId = null;
	$.ajax({type: "POST", url: "PaulaDeployerServlet", data: {formName: "LoraSniffer", action: "stop", session: true}});
	$('#map-screen').hide();
	$('#main-screen').show();
}

function initSignalMap(){
	smMap = L.map('signal-map', {zoomControl: true, attributionControl: true}).setView(SM_DEFAULT_CENTER, 16);
	smMap.on('dragstart', function(){ smUserMoved = true; });
	L.tileLayer(SM_TILES, {maxZoom: 20, maxNativeZoom: 19, attribution: 'Imagery &copy; Esri'}).addTo(smMap);
	// No GPS (blocked, or not yet): tap the map where you are standing.
	smMap.on('click', function(e){
		if(smMe && !smMe.manual) return;
		setSignalMapMe(e.latlng.lat, e.latlng.lng, 10, true);
	});
}

// ---- Paula's position = the phone's

function startSignalMapLocation(){
	if(!navigator.geolocation || !window.isSecureContext){
		showSignalMapLocationHelp('blocked');
		return;
	}
	$('#map-position').text('Waiting for the phone\'s GPS...');
	smWatchId = navigator.geolocation.watchPosition(function(pos){
		setSignalMapMe(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy, false);
	}, function(err){
		showSignalMapLocationHelp(err.code === 1 ? 'blocked' : 'unavailable');
	}, {enableHighAccuracy: true, maximumAge: 5000, timeout: 30000});
}

// Chrome only gives the GPS to https pages (or ones it is told to trust) - this page is plain http.
function showSignalMapLocationHelp(why){
	if(smMe && !smMe.manual) return;
	var html = why === 'blocked'
		? '<strong>Phone location blocked</strong> (this page is http). One-time fix in Chrome: open <code>chrome://flags</code>, search '
		  + '<em>Insecure origins treated as secure</em>, add <code>' + escapeHtml(location.origin) + '</code>, Enable, Relaunch. '
		: '<strong>No GPS fix</strong> - check the phone\'s location is on. ';
	html += 'Meanwhile, tap the map where you are.';
	$('#map-position').html(html);
}

function setSignalMapMe(lat, lon, acc, manual){
	smMe = {lat: lat, lon: lon, acc: acc, manual: manual};
	$('#map-position').html(manual ? 'Position set by hand - tap again to move it' : 'GPS &plusmn;' + Math.round(acc) + ' m');
	if(!smMeMarker){
		smMeMarker = L.marker([lat, lon], {
			icon: L.icon({iconUrl: 'assets/images/paula-logo.svg', iconSize: [34, 36], iconAnchor: [17, 18]}),
			zIndexOffset: 1000
		}).addTo(smMap);
		smMeCircle = L.circle([lat, lon], {radius: acc, color: '#1c7ed6', weight: 1, fillOpacity: 0.08, interactive: false}).addTo(smMap);
	}else{
		smMeMarker.setLatLng([lat, lon]);
		smMeCircle.setLatLng([lat, lon]).setRadius(acc);
	}
	renderSignalMap();
}

// ---- What Paula hears

function pollSignalMap(){
	var params = {formName: "LoraSniffer", action: "poll", after: smSequence};
	if(smMe){ params.lat = smMe.lat; params.lon = smMe.lon; params.acc = smMe.acc; }
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: params,
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS || !smPollTimer) return;
			var data = JSON.parse(result[DATA_KEY]);
			if(data.sequence < smSequence) smSequence = 0;  // Tomcat restarted
			var changed = false;
			data.packets.forEach(function(p){
				if(p.sequence <= smSequence) return;
				smSequence = p.sequence;
				if(noteSignalMapPacket(p)) changed = true;
			});
			if(changed) renderSignalMap();
			$('#map-status').text(data.connected ? 'Listening...' : 'Paula not connected - is it plugged in?');
		}
	});
}

// False for packets that say nothing about a device: garbled, failed checks, unknown sizes, and
// VitalSigns from a device whose name hasn't come in a data packet yet.
function noteSignalMapPacket(p){
	if(p.raw || p.ok === false || p.type === 'Unknown' || !p.type) return false;
	var annabelle = p.type === 'WeatherForecastUpdate';
	if(!annabelle && /^Vital signs /.test(p.label)) return false;
	var key = annabelle ? 'Annabelle' : p.label;
	var h = smHeard[key];
	if(!h || h.fromNearby) h = smHeard[key] = {key: key, name: key, count: 0, annabelle: annabelle, serial: h ? h.serial : undefined};
	h.rssi = p.rssi;
	h.snr = p.snr;
	h.at = Date.now();
	h.count++;
	if(p.serial && !/^VitalSigns/.test(p.type)){ h.serial = p.serial; h.type = p.type; }
	if(p.lat !== undefined){ h.lat = p.lat; h.lon = p.lon; }
	// where Paula was when it heard it: the Pi's stamp, else the phone now
	if(p.plat !== undefined){ h.hereLat = p.plat; h.hereLon = p.plon; }
	else if(smMe){ h.hereLat = smMe.lat; h.hereLon = smMe.lon; }
	else { h.hereLat = h.hereLon = undefined; }
	return true;
}

function loadSignalMapNearby(){
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: {formName: "NearbyDevices", action: "list"},
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS) return;
			var listing = JSON.parse(result[DATA_KEY]), now = Date.now();
			showNearbyClock(listing);
			smNearby = {};
			(listing.devices || []).forEach(function(d){
				smNearby[d.serial] = d;
				// Daffodils Paula heard before this screen was opened (the sniffer was off): listed from
				// Paula's table until the sniffer hears them - no line, Paula's position then is unknown.
				var key = d.name || d.serial, h = smHeard[key];
				if(h && !h.fromNearby) return;
				smHeard[key] = {key: key, name: key, serial: d.serial, type: 'DigitalStablesData', rssi: d.rssi,
					at: now - d.ageSec * 1000, count: 0, fromNearby: true};
			});
			renderSignalMap();
		}
	});
}

function signalMapPositions(params){
	params.formName = 'SignalMap';
	$.ajax({
		type: "POST", url: "PaulaDeployerServlet", data: params,
		success: function(raw){
			var result = JSON.parse(raw);
			if(result[STATUS_KEY] !== STATUS_SUCCESS){ alert(result[DATA_KEY]); return; }
			smSaved = JSON.parse(result[DATA_KEY]).positions || {};
			renderSignalMap();
		},
		error: function(){ if(params.action !== 'list') alert('Request failed.'); }
	});
}

// ---- Drawing

// Same cut-offs as the Lora Sniffer's colours (SF9: sensitivity is about -126 dBm, so -110 leaves ~15 dB).
function signalClass(rssi){ return rssi >= -95 ? 'good' : (rssi >= -110 ? 'fair' : 'weak'); }
var SM_COLOURS = {good: '#2f9e44', fair: '#f08c00', weak: '#e03131', none: '#868e96'};
function signalBars(rssi){ return rssi >= -85 ? 4 : rssi >= -95 ? 3 : rssi >= -110 ? 2 : 1; }

function signalMapPosition(key){
	if(smSaved[key]) return {lat: smSaved[key].lat, lon: smSaved[key].lon, saved: true};
	var h = smHeard[key];
	if(h && h.lat !== undefined) return {lat: h.lat, lon: h.lon, saved: false};
	return null;
}

function metresBetween(aLat, aLon, bLat, bLon){
	var r = 6371000, toRad = Math.PI / 180;
	var dLat = (bLat - aLat) * toRad, dLon = (bLon - aLon) * toRad;
	var s = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(aLat * toRad) * Math.cos(bLat * toRad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
	return 2 * r * Math.asin(Math.sqrt(s));
}
function distanceText(m){ return m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(2) + ' km'; }

function renderSignalMap(){
	if(!smMap) return;
	var now = Date.now();
	// Annabelle drawn even before it is heard, if its position is saved - so you see where it is.
	var keys = Object.keys(smHeard);
	if(smSaved.Annabelle && !smHeard.Annabelle) keys.push('Annabelle');
	Object.keys(smLayers).forEach(function(k){ if(keys.indexOf(k) < 0) removeSignalMapLayer(k); });
	keys.forEach(function(key){
		var h = smHeard[key], pos = signalMapPosition(key);
		if(!pos){ removeSignalMapLayer(key); return; }
		var cls = h ? signalClass(h.rssi) : 'none', colour = SM_COLOURS[cls];
		var ageSec = h ? (now - h.at) / 1000 : Infinity;
		var opacity = ageSec < SM_FRESH_SEC ? 0.95 : (ageSec < SM_OLD_SEC ? 0.5 : 0.25);
		var layer = smLayers[key] || (smLayers[key] = {});
		var isAnnabelle = key === 'Annabelle';
		if(!layer.marker){
			layer.marker = L.circleMarker([pos.lat, pos.lon], {radius: isAnnabelle ? 12 : 9, weight: isAnnabelle ? 4 : 2, color: '#fff', fillOpacity: 1})
				.bindTooltip(escapeHtml(key), {permanent: true, direction: 'top', offset: [0, -10], className: 'sm-label'})
				.addTo(smMap);
		}
		layer.marker.setLatLng([pos.lat, pos.lon]).setStyle({fillColor: colour, opacity: opacity, fillOpacity: Math.max(opacity, 0.5)});
		if(h && h.hereLat !== undefined){
			var pts = [[h.hereLat, h.hereLon], [pos.lat, pos.lon]];
			var style = {color: colour, weight: isAnnabelle ? 5 : 4, opacity: opacity, dashArray: ageSec < SM_OLD_SEC ? null : '6 8', interactive: false};
			if(!layer.line) layer.line = L.polyline(pts, style).addTo(smMap);
			else layer.line.setLatLngs(pts).setStyle(style);
			layer.line.bringToBack();
		}else if(layer.line){
			smMap.removeLayer(layer.line);
			layer.line = null;
		}
	});
	renderSignalMapBanner(now);
	renderSignalMapList(now);
	if(!smUserMoved) fitSignalMap(false);
}

function removeSignalMapLayer(key){
	var layer = smLayers[key];
	if(!layer) return;
	if(layer.marker) smMap.removeLayer(layer.marker);
	if(layer.line) smMap.removeLayer(layer.line);
	delete smLayers[key];
}

// Paula and every device with a position in view - on its own only when that number has grown;
// force (Show all): always, and the view follows again.
function fitSignalMap(force){
	if(!smMap) return;
	var pts = [];
	if(smMe) pts.push([smMe.lat, smMe.lon]);
	Object.keys(smHeard).concat(Object.keys(smSaved)).forEach(function(k){
		var pos = signalMapPosition(k);
		if(pos) pts.push([pos.lat, pos.lon]);
	});
	if(!pts.length){ if(force) alert('Nothing with a position yet.'); return; }
	if(!force && pts.length <= smFittedCount) return;
	if(force) smUserMoved = false;
	smFittedCount = pts.length;
	if(pts.length === 1) smMap.setView(pts[0], 17);
	else smMap.fitBounds(pts, {padding: [40, 40], maxZoom: 18});
}

function barsHtml(rssi, cls){
	var n = rssi === undefined ? 0 : signalBars(rssi), html = '<span class="sm-bars sm-' + cls + '">';
	for(var i = 1; i <= 4; i++) html += '<i style="height:' + (4 + i * 4) + 'px" class="' + (i <= n ? 'on' : '') + '"></i>';
	return html + '</span>';
}

function heardAgo(h, now){ return nearbyAgo(Math.max(0, Math.round((now - h.at) / 1000))); }

// Annabelle from here: the gateway's forecast, as heard at this spot.
function renderSignalMapBanner(now){
	var h = smHeard.Annabelle, $b = $('#map-annabelle');
	if(!h){
		$b.attr('class', 'sm-banner sm-banner-none').html(barsHtml(undefined, 'none')
			+ '<div><strong>Annabelle: not heard yet</strong><div class="sm-banner-sub">Its weather forecast goes out every few minutes - stay a while. Nothing after ~10 min: Annabelle can\'t reach this spot.</div></div>');
		return;
	}
	var cls = signalClass(h.rssi), ageSec = (now - h.at) / 1000;
	var verdict = cls === 'good' ? 'strong - a device here should reach it' : (cls === 'fair' ? 'fair - should work, little margin' : 'weak - a device here may need a relay');
	var where = '';
	if(smMe && h.hereLat !== undefined){
		var moved = metresBetween(smMe.lat, smMe.lon, h.hereLat, h.hereLon);
		if(moved > 30) where = ' &middot; heard ' + distanceText(moved) + ' from here';
	}
	$b.attr('class', 'sm-banner sm-banner-' + (ageSec < SM_OLD_SEC ? cls : 'none')).html(barsHtml(h.rssi, cls)
		+ '<div><strong>Annabelle: ' + verdict + '</strong><div class="sm-banner-sub">R ' + h.rssi + ' dBm &middot; SNR ' + Number(h.snr).toFixed(1)
		+ ' &middot; ' + heardAgo(h, now) + where + '</div></div>');
}

function renderSignalMapList(now){
	var keys = Object.keys(smHeard).filter(function(k){ return k !== 'Annabelle'; });
	// heard in the last 3 min first, strongest first; then the rest, most recent first
	keys.sort(function(a, b){
		var ha = smHeard[a], hb = smHeard[b];
		var fa = now - ha.at < SM_FRESH_SEC * 1000, fb = now - hb.at < SM_FRESH_SEC * 1000;
		if(fa !== fb) return fa ? -1 : 1;
		return fa ? hb.rssi - ha.rssi : hb.at - ha.at;
	});
	if(smHeard.Annabelle || smSaved.Annabelle) keys.unshift('Annabelle');
	if(!keys.length){
		$('#map-list').html('<div class="text-muted" style="padding:8px 0;">Nothing heard yet. Awake devices send about every minute, sleeping ones when they wake.</div>');
		return;
	}
	$('#map-list').html(keys.map(function(key){ return signalMapRowHtml(key, now); }).join(''));
}

function signalMapRowHtml(key, now){
	var h = smHeard[key], pos = signalMapPosition(key);
	var cls = h ? signalClass(h.rssi) : 'none';
	var line1 = !h ? 'not heard yet' : 'R ' + h.rssi + (h.snr !== undefined ? ' &middot; SNR ' + Number(h.snr).toFixed(1) : '')
		+ ' &middot; ' + heardAgo(h, now) + (h.count ? ' &middot; ' + h.count + 'x' : ' (before this screen)');
	var place = '';
	if(pos && smMe) place = distanceText(metresBetween(smMe.lat, smMe.lon, pos.lat, pos.lon)) + ' away' + (pos.saved ? ' (saved position)' : '');
	else if(pos) place = pos.saved ? 'saved position' : 'position from the device';
	else place = '<span style="color:#b45309">no position - stand next to it and Save here</span>';
	var buttons = '<button type="button" class="btn btn-outline-secondary btn-sm sm-btn sm-save-here" data-key="' + escapeHtml(key) + '">Save here</button>';
	if(smSaved[key]) buttons += '<button type="button" class="btn btn-outline-danger btn-sm sm-btn sm-clear-saved" data-key="' + escapeHtml(key) + '">Clear</button>';
	// Daffodils: the Nearby Devices WiFi switch
	var wifi = '';
	var d = h && h.serial ? smNearby[h.serial] : null;
	if(d){
		var on = d.wifi > 0, c = d.cmd, pending = c && c.state === 1;
		wifi = '<div class="sm-row-wifi">' + (on ? '<span style="color:#2e7d32">&#10003; WiFi on</span> - ' + escapeHtml(NEARBY_WIFI[d.wifi] || ('status ' + d.wifi))
			: '<span class="text-danger">&#10007; WiFi off</span>') + ' &middot; ' + Number(d.battery).toFixed(2) + ' V &middot; ' + (d.sleep > 0 ? 'asleep ' + d.sleep + ' s' : 'awake');
		if(pending) wifi += '<div style="color:#ef6c00">Turning ' + (c.on ? 'on' : 'off') + '... sent ' + c.sends + 'x' + (d.sleep > 0 ? ' - answers when it wakes' : '') + '</div>';
		else if(c && c.state === 3) wifi += '<div class="text-danger">Gave up after 15 min - device not heard</div>';
		wifi += '</div>';
		buttons = (pending ? '<button type="button" class="btn btn-outline-secondary btn-sm sm-btn" disabled>Waiting...</button>'
			: '<button type="button" class="btn ' + (on ? 'btn-outline-secondary' : 'btn-primary') + ' btn-sm sm-btn sm-wifi" data-serial="' + escapeHtml(d.serial) + '" data-state="' + (on ? 'off' : 'on') + '">'
			  + (on ? 'WiFi off' : 'WiFi on') + '</button>') + buttons;
	}
	return '<div class="sm-row">'
		+ '<div class="sm-row-top">' + barsHtml(h ? h.rssi : undefined, cls) + '<strong class="sm-row-name">' + escapeHtml(key) + '</strong></div>'
		+ '<div class="sm-row-detail">' + line1 + '</div>'
		+ '<div class="sm-row-detail">' + place + '</div>' + wifi
		+ '<div class="sm-row-buttons">' + buttons + '</div></div>';
}

function showSignalMapCapture(capture){
	if(!capture){ $('#map-capture').text('Not being saved - see the Pi\'s log'); return; }
	$('#map-capture').html('Saving to <a href="captures/' + escapeHtml(capture) + '" target="_blank">captures/' + escapeHtml(capture) + '</a>');
}
