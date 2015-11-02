"use strict";


/*---- Global variables ----*/

function elemId(s) {  // Abbreviated function name
	return document.getElementById(s);
}

// Document nodes (elements)
const htmlElem                = document.documentElement;
const windowListElem          = elemId("window-list");
const messageListElem         = elemId("message-list");
const memberListContainerElem = elemId("member-list-container");
const memberListElem          = elemId("member-list");
const nicknameElem            = elemId("nickname");


/* Main state */

// Most variables are null before getState() returns successfully. Thereafter, most of them are non-null.

// Type tuple<str profile, str party, str concatenated>.
// Is null if windowNames is null or zero-length, otherwise this[2] equals an entry in windowNames.
var activeWindow = null;

// Type list<str>. Length 0 or more. Each element is of the form (profile+"\n"+party).
// Elements can be in any order, and it determines the order rendered on screen.
var windowNames = null;

// Type map<str,window>. Key is an entry in windowNames. Each window has these properties:
// - list<list<int seq, int flags, int timestamp, str... payload>> lines
// - int markedReadUntil
// - int numNewMessages
var windowData = null;

// Type map<str,object>. Key is the network profile name. Each object has these properties:
// - str currentNickname
// - map<str,object> channels, with values having {"members" -> list<str>, "topic" -> str or null}
var connectionData = null;

// Type int. At least 0.
var nextUpdateId = null;

// Type bool.
var optimizeMobile = false;

// In milliseconds. This value changes during execution depending on successful/failed requests.
var retryTimeout = 1000;


/* Miscellaneous values */

// Configurable parameter. Used by getState().
var maxMessagesPerWindow = 3000;

// Type map<str,int>. It is a collection of integer constants, defined in Java code to avoid duplication. Values are set by getState().
var Flags = null;



/*---- User interface functions ----*/

// Called once after the script and page are loaded.
function init() {
	var cookieParts = document.cookie.split(";");
	cookieParts.forEach(function(s) {
		s = s.trim();
		if (s.startsWith("optimize-mobile="))
			optimizeMobile = s.substring(16) == "true";
	});
	if (optimizeMobile)
		maxMessagesPerWindow = 500;
	
	Notification.requestPermission();
	getState();
}


// Called only by getState(). inData is a object parsed from JSON text.
function loadState(inData) {
	// Set simple fields
	nextUpdateId = inData.nextUpdateId;
	connectionData = inData.connections;
	Flags = inData.flagsConstants;
	
	// Handle the windows
	windowNames = [];
	windowData = {};
	inData.windows.forEach(function(inWindow) {
		// 'inWindow' has type tuple<str profile, str party, window state>
		var windowName = inWindow[0] + "\n" + inWindow[1];
		if (windowNames.indexOf(windowName) != -1)
			throw "Duplicate window";
		windowNames.push(windowName);
		
		// Preprocess the window's lines
		var inState = inWindow[2];
		var prevTimestamp = 0;
		inState.lines.forEach(function(line) {
			line[2] += prevTimestamp;  // Delta decoding
			prevTimestamp = line[2];
		});
		var outState = createBlankWindow();
		for (var key in inState)
			outState[key] = inState[key];
		windowData[windowName] = outState;
	});
	activeWindow = null;
	windowNames.sort();
	
	// Update UI elements
	redrawWindowList();
	if (windowNames.length > 0)
		setActiveWindow(windowNames[0]);
}


// Clears the window list HTML container element and rebuilds it from scratch based on
// the current states of windowNames, windowData[windowName].newMessages, and activeWindow.
function redrawWindowList() {
	removeChildren(windowListElem);
	windowNames.forEach(function(windowName) {
		// windowName has type str, and is of the form (profile+"\n"+party)
		var parts = windowName.split("\n");
		var profile = parts[0];
		var party = parts[1];
		
		// Create the anchor element
		var a = document.createElement("a");
		var s = party != "" ? party : profile;
		var n = windowData[windowName].numNewMessages;
		if (n > 0)
			s += " (" + n + ")";
		setElementText(a, s);
		a.href = "#";
		a.onclick = function() {
			setActiveWindow(windowName);
			return false;
		};
		var menuItems = [];
		if (windowData[windowName].isMuted)
			menuItems.push(["Unmute window", function() { windowData[windowName].isMuted = false; }]);
		else {
			menuItems.push(["Mute window", function() {
				windowData[windowName].isMuted = true;
				windowData[windowName].numNewMessages = 0;
				redrawWindowList();
			}]);
		}
		if (party == "" && profile in connectionData || profile in connectionData && party in connectionData[profile].channels)
			menuItems.push(["Close window", null]);
		else
			menuItems.push(["Close window", function() { sendAction([["close-window", profile, party]], null, null); }]);
		a.oncontextmenu = menuModule.makeOpener(menuItems);
		
		var li = document.createElement("li");
		li.appendChild(a);
		if (party == "")
			li.className = "profile";
		windowListElem.appendChild(li);
	});
	refreshWindowSelection();
	
	var totalNewMsg = 0;
	for (var key in windowData)
		totalNewMsg += windowData[key].numNewMessages;
	if (activeWindow != null)
		document.title = (totalNewMsg > 0 ? "(" + totalNewMsg + ") " : "") + (activeWindow[1] != "" ? activeWindow[1] + " - " : "") + activeWindow[0] + " - MamIRC";
}


// Refreshes the selection class of each window <li> element based on the states of windowNames and activeWindow.
// This assumes that the list of HTML elements is already synchronized with windowNames.
function refreshWindowSelection() {
	if (activeWindow == null)
		return;
	var windowLis = windowListElem.getElementsByTagName("li");
	windowNames.forEach(function(name, i) {
		if (name == activeWindow[2])
			windowLis[i].classList.add("selected");
		else
			windowLis[i].classList.remove("selected");
	});
}


// Refreshes the channel members text element based on the states of
// connectionData[profileName].channels[channelName].members and activeWindow.
function redrawChannelMembers() {
	removeChildren(memberListElem);
	var profile = activeWindow[0], party = activeWindow[1];
	if (profile in connectionData && party in connectionData[profile].channels) {
		var members = connectionData[profile].channels[party].members;
		members.sort(function(s, t) {  // Safe mutation; case-insensitive ordering
			return s.toLowerCase().localeCompare(t.toLowerCase());
		});
		members.forEach(function(name) {
			var li = document.createElement("li");
			setElementText(li, name);
			li.oncontextmenu = menuModule.makeOpener([["Open PM window", function() { openPrivateMessagingWindow(name, null); }]]);
			memberListElem.appendChild(li);
		});
		memberListContainerElem.style.removeProperty("display");
	} else
		memberListContainerElem.style.display = "none";
}


// Changes activeWindow and redraws the user interface. 'name' must exist in the array windowNames.
// Note that for efficiency, switching to the already active window does not re-render the table of lines.
// Thus all other logic must update the active window's lines incrementally whenever new updates arrive.
function setActiveWindow(name) {
	// activeWindow may be null at the start of this method, but will be non-null afterward
	windowData[name].numNewMessages = 0;
	if (activeWindow != null && activeWindow[2] == name) {
		redrawWindowList();
		return;
	}
	
	// Set state, refresh text, refresh window selection
	activeWindow = name.split("\n").concat(name);
	setElementText(nicknameElem, (activeWindow[0] in connectionData ? connectionData[activeWindow[0]].currentNickname : ""));
	redrawWindowList();
	redrawChannelMembers();
	
	// Redraw all message lines in this window
	removeChildren(messageListElem);
	windowData[name].lines.forEach(function(line) {
		// 'line' has type tuple<int seq, int timestamp, str line, int flags>
		messageListElem.appendChild(lineDataToRowElem(line));
	});
	reflowMessagesTable();
	window.scrollTo(0, document.documentElement.scrollHeight);
}


function reflowMessagesTable() {
	var tableElem = messageListElem.parentNode;
	tableElem.style.tableLayout = "auto";
	if (messageListElem.children.length > 0) {
		var cols = messageListElem.firstChild.children;
		var widths = [cols[0].clientWidth, cols[1].clientWidth];
		tableElem.style.tableLayout = "fixed";
		cols[0].style.width = widths[0] + "px";
		cols[1].style.width = widths[1] + "px";
	}
}


// Converts a window line (which is a tuple of str/int) into a <tr> element for the main messages table.
// The window line comes from windowData[windowName].lines[i] (which can be from loadState() or loadUpdates()).
// This function can only be called for lines in the active window; it must not be used for off-screen windows.
function lineDataToRowElem(line) {
	// Input variables
	const sequence = line[0];
	const flags = line[1];
	const timestamp = line[2];
	const payload = line.slice(3);
	const type = flags & Flags.TYPE_MASK;
	
	// Output variables
	var who = "*";         // Type str
	var lineElems = [];    // Type list<domnode>
	var quoteText = null;  // Type str or null
	var tr = document.createElement("tr");
	
	// Take action depending on head of payload
	if (type == Flags.PRIVMSG) {
		who = payload[0];
		var s = payload[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
		
		if ((flags & Flags.OUTGOING) != 0)
			tr.classList.add("outgoing");
		if ((flags & Flags.NICKFLAG) != 0)
			tr.classList.add("nickflag");
		quoteText = s.replace(/\t/g, " ").replace(REMOVE_FORMATTING_REGEX, "");
		lineElems = fancyTextToElems(s);
		if (mematch != null) {
			tr.classList.add("me-action");
			quoteText = "* " + who + " " + quoteText;
		} else {
			quoteText = "<" + who + "> " + quoteText;
		}
		
	} else if (type == Flags.NOTICE) {
		who = "(" + payload[0] + ")";
		lineElems = fancyTextToElems(payload[1]);
	} else if (type == Flags.NICK) {
		lineElems.push(document.createTextNode(payload[0] + " changed their name to " + payload[1]));
	} else if (type == Flags.JOIN) {
		who = "\u2192";  // Rightwards arrow
		lineElems.push(document.createTextNode(payload[0] + " joined the channel"));
	} else if (type == Flags.PART) {
		who = "\u2190";  // Leftwards arrow
		lineElems.push(document.createTextNode(payload[0] + " left the channel"));
	} else if (type == Flags.QUIT) {
		who = "\u2190";  // Leftwards arrow
		lineElems = fancyTextToElems(payload[1]);
		lineElems.splice(0, 0, document.createTextNode(payload[0] + " has quit: "));
	} else if (type == Flags.KICK) {
		who = "\u2190";  // Leftwards arrow
		lineElems = fancyTextToElems(payload[2]);
		lineElems.splice(0, 0, document.createTextNode(payload[1] + " was kicked by " + payload[0] + ": "));
	} else if (type == Flags.TOPIC) {
		lineElems = fancyTextToElems(payload[1]);
		lineElems.splice(0, 0, document.createTextNode(payload[0] + " set the channel topic to: "));
	} else if (type == Flags.INITNOTOPIC) {
		lineElems.push(document.createTextNode("No channel topic is set"));
	} else if (type == Flags.INITTOPIC) {
		lineElems = fancyTextToElems(payload[0]);
		lineElems.splice(0, 0, document.createTextNode("The channel topic is: "));
	} else if (type == Flags.SERVERREPLY) {
		who = "*";
		lineElems = fancyTextToElems(payload[1]);
	} else if (type == Flags.NAMES) {
		who = "*";
		lineElems.push(document.createTextNode("Users in channel: " + payload.join(", ")));
	} else if (type == Flags.MODE) {
		who = "*";
		lineElems.push(document.createTextNode(payload[0] + " set mode " + payload[1]));
	} else if (type == Flags.DISCONNECTED) {
		lineElems.push(document.createTextNode("Disconnected from server"));
	} else {
		who = "RAW";
		lineElems.push(document.createTextNode("flags=" + flags + " " + payload.join(" ")));
	}
	
	// Make timestamp cell
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(timestamp * 1000)));
	tr.appendChild(td);
	
	// Make nickname cell
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	if (who != "*" && who != "RAW")
		td.oncontextmenu = menuModule.makeOpener([["Open PM window", function() { openPrivateMessagingWindow(who, null); }]]);
	tr.appendChild(td);
	
	// Make message cell and its sophisticated context menu
	td = document.createElement("td");
	lineElems.forEach(function(elem) {
		td.appendChild(elem);
	});
	var menuItems = [["Quote text", null]];
	if (quoteText != null) {
		menuItems[0][1] = function() {
			inputBoxModule.putText(quoteText);
		};
	}
	menuItems.push(["Mark read to here", function() { sendAction([["mark-read", activeWindow[0], activeWindow[1], sequence + 1]], null, null); }]);
	menuItems.push(["Clear to here", function() {
		if (confirm("Do you want to clear text?"))
			sendAction([["clear-lines", activeWindow[0], activeWindow[1], sequence + 1]], null, null);
	}]);
	td.oncontextmenu = menuModule.makeOpener(menuItems);
	tr.appendChild(td);
	
	// Finishing touches
	if (sequence < windowData[activeWindow[2]].markedReadUntil)
		tr.classList.add("read");
	else
		tr.classList.add("unread");
	return tr;
}

const ME_INCOMING_REGEX = /^\u0001ACTION (.*)\u0001$/;
const REMOVE_FORMATTING_REGEX = /[\u0002\u000F\u0016\u001D\u001F]|\u0003(?:\d{1,2}(?:,\d{1,2})?)?/g;


// Given a string with possible IRC formatting control codes and plain text URLs,
// this returns an array of DOM nodes representing text with formatting and anchor links.
function fancyTextToElems(str) {
	// Take fast path if string contains no formatting or potential URLs
	if (!SPECIAL_FORMATTING_REGEX.test(str))
		return [document.createTextNode(str)];
	
	// Current formatting state
	var bold = false;
	var italic = false;
	var underline = false;
	var background = 0;
	var foreground = 1;
	
	// Process formatting commands and chunks of text
	var result = [];
	while (str != "") {
		var formatMatch = FORMAT_CODE_REGEX.exec(str);
		var strPartEnd = formatMatch != null ? formatMatch[1].length : str.length;
		if (strPartEnd > 0) {
			// Process text
			var chunk = str.substr(0, strPartEnd);
			var elems = [];
			while (chunk != "") {
				var urlMatch = URL_REGEX0.exec(chunk);
				if (urlMatch == null)
					urlMatch = URL_REGEX1.exec(chunk);
				var chunkPartEnd = urlMatch != null ? urlMatch[1].length : chunk.length;
				if (chunkPartEnd > 0)
					elems.push(document.createTextNode(chunk.substr(0, chunkPartEnd)));
				if (urlMatch == null)
					break;
				var a = document.createElement("a");
				a.href = urlMatch[2];
				a.target = "_blank";
				setElementText(a, urlMatch[2]);
				elems.push(a);
				chunk = chunk.substring(urlMatch[0].length);
			}
			
			if (background != 0 || foreground != 1) {
				var elem = document.createElement("span");
				if (background != 0)
					elem.style.backgroundColor = TEXT_COLORS[background];
				if (foreground != 1)
					elem.style.color = TEXT_COLORS[foreground];
				elems.forEach(function(e) {
					elem.appendChild(e);
				});
				elems = [elem];
			}
			var temp = [[bold, "b"], [italic, "i"], [underline, "u"]];
			temp.forEach(function(pair) {
				if (pair[0]) {
					var elem = document.createElement(pair[1]);
					elems.forEach(function(e) {
						elem.appendChild(e);
					});
					elems = [elem];
				}
			});
			elems.forEach(function(e) {
				result.push(e);
			});
		}
		if (formatMatch == null)
			break;
		
		// Process format code
		switch (str.charCodeAt(strPartEnd)) {
			case 0x02:
				bold = !bold;
				break;
			case 0x1D:
				italic = !italic;
				break;
			case 0x1F:
				underline = !underline;
				break;
			case 0x16:  // Reverse
				var temp = foreground;
				foreground = background;
				background = temp;
				break;
			case 0x0F:  // Plain
				bold = false;
				italic = false;
				underline = false;
				background = 0;
				foreground = 1;
				break;
			case 0x03:  // Color
				var fore = formatMatch[2] != undefined ? parseInt(formatMatch[2], 10) : 1;
				var back = formatMatch[3] != undefined ? parseInt(formatMatch[3], 10) : 0;
				if (fore < TEXT_COLORS.length) foreground = fore;
				if (back < TEXT_COLORS.length) background = back;
				break;
			default:
				throw "Assertion error";
		}
		str = str.substring(formatMatch[0].length);
	}
	
	// Epilog
	if (result.length == 0)  // Prevent having an empty <td> to avoid style/display problems
		result.push(document.createTextNode(""));
	return result;
}

const SPECIAL_FORMATTING_REGEX = /[\u0002\u0003\u000F\u0016\u001D\u001F]|https?:\/\//;
const FORMAT_CODE_REGEX = /^(.*?)(?:[\u0002\u000F\u0016\u001D\u001F]|\u0003(?:(\d{1,2})(?:,(\d{1,2}))?)?)/;
const URL_REGEX0 = /^(|.*? )(https?:\/\/[^ ]+)/;
const URL_REGEX1 = /^(.*?\()(https?:\/\/[^ ()]+)/;
const TEXT_COLORS = [
	"#FFFFFF", "#000000", "#00007F", "#009300",
	"#FF0000", "#7F0000", "#9C009C", "#FC7F00",
	"#FFFF00", "#00FC00", "#009393", "#00FFFF",
	"#0000FC", "#FF00FF", "#7F7F7F", "#D2D2D2",
];


function loadUpdates(inData) {
	nextUpdateId = inData.nextUpdateId;
	
	const scrollToBottom = elemId("input-box").getBoundingClientRect().bottom < document.documentElement.clientHeight;
	const scrollPosition = document.documentElement.scrollTop;
	var activeWindowUpdated = false;
	inData.updates.forEach(function(payload) {
		var type = payload[0];
		
		if (type == "APPEND") {
			var windowName = payload[1] + "\n" + payload[2];
			var newWindow = false;
			if (windowNames.indexOf(windowName) == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = createBlankWindow();
				redrawWindowList();
				newWindow = true;
			}
			var line = payload.slice(3);
			var lines = windowData[windowName].lines;
			lines.push(line);
			var numPrefixDel = Math.max(lines.length - maxMessagesPerWindow, 0);
			lines.splice(0, numPrefixDel);
			if (activeWindow != null && windowName == activeWindow[2]) {
				messageListElem.appendChild(lineDataToRowElem(line));
				for (var i = 0; i < numPrefixDel; i++)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
			var subtype = line[1] & Flags.TYPE_MASK;
			if (subtype == Flags.PRIVMSG) {
				if (activeWindow != null && windowName == activeWindow[2] && (line[1] & Flags.OUTGOING) != 0)
					windowData[windowName].numNewMessages = 0;
				else if (!windowData[windowName].isMuted)
					windowData[windowName].numNewMessages++;
				redrawWindowList();
				if (!windowData[windowName].isMuted) {
					var notiftext = null;
					if (!payload[2].startsWith("#") && !payload[2].startsWith("&") && (newWindow || (line[1] & Flags.NICKFLAG) != 0)) {
						// New private messaging window popped open, or nickflagged in one
						var match = ME_INCOMING_REGEX.exec(line[4]);
						if (match == null)
							notiftext = "<" + line[3] + "> " + line[4].replace(REMOVE_FORMATTING_REGEX, "");
						else
							notiftext = "* " + line[3] + " " + match[1].replace(REMOVE_FORMATTING_REGEX, "");
					} else if ((line[1] & Flags.NICKFLAG) != 0) {
						var match = ME_INCOMING_REGEX.exec(line[4]);
						if (match == null)
							notiftext = payload[2] + " <" + line[3] + "> " + line[4].replace(REMOVE_FORMATTING_REGEX, "");
						else
							notiftext = payload[2] + " * " + line[3] + " " + match[1].replace(REMOVE_FORMATTING_REGEX, "");
					}
					if (notiftext != null) {
						var opts = {icon: "tomoe-mami-icon-text.png"};
						var notif = new Notification(notiftext, opts);
						notif.onclick = function() { setActiveWindow(windowName); };
					}
				}
			} else if (subtype == Flags.JOIN || subtype == Flags.PART || subtype == Flags.QUIT || subtype == Flags.KICK || subtype == Flags.NICK) {
				var members = connectionData[payload[1]].channels[payload[2]].members;
				var name = line[3];
				if (subtype == Flags.JOIN && members.indexOf(name) == -1)
					members.push(name);
				else if (subtype == Flags.PART && members.indexOf(name) != -1)
					members.splice(members.indexOf(name), 1);
				else if ((subtype == Flags.QUIT || subtype == Flags.KICK) && members.indexOf(name) != -1)
					members.splice(members.indexOf(name), 1);
				else if (subtype == Flags.NICK) {
					if (members.indexOf(name) != -1)
						members.splice(members.indexOf(name), 1);
					if (members.indexOf(line[4]) == -1)
						members.push(line[4]);
				}
				if (activeWindow != null && windowName == activeWindow[2])
					redrawChannelMembers();
			} else if (subtype == Flags.TOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = line[4];
			} else if (subtype == Flags.INITNOTOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = null;
			} else if (subtype == Flags.INITTOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = line[3];
			} else if (subtype == Flags.SERVERREPLY) {
				if (!windowData[windowName].isMuted) {
					windowData[windowName].numNewMessages++;
					redrawWindowList();
				}
			} else if (subtype == Flags.NAMES) {
				connectionData[payload[1]].channels[payload[2]].members = line.slice(3);
				if (activeWindow != null && payload[1] == activeWindow[0] && payload[2] == activeWindow[1])
					redrawChannelMembers();
			} else if (subtype == Flags.DISCONNECTED && payload[2] == "") {
				delete connectionData[payload[1]];
			}
		} else if (type == "MYNICK") {
			var profile = payload[1];
			var name = payload[2];
			connectionData[profile].currentNickname = name;
			if (activeWindow != null && activeWindow[0] == profile) {
				setElementText(nicknameElem, name);
				activeWindowUpdated = true;
			}
		} else if (type == "JOINED") {
			connectionData[payload[1]].channels[payload[2]] = {
				members: [],
				topic: null,
			};
		} else if (type == "PARTED" || type == "KICKED") {
			delete connectionData[payload[1]].channels[payload[2]];
			if (activeWindow != null && activeWindow[0] == payload[1] && activeWindow[1] == payload[2])
				redrawChannelMembers();
		} else if (type == "OPENWIN") {
			var windowName = payload[1] + "\n" + payload[2];
			var index = windowNames.indexOf(windowName);
			if (index == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = createBlankWindow();
				redrawWindowList();
				inputBoxModule.clearText();
				setActiveWindow(windowName);
			}
		} else if (type == "CLOSEWIN") {
			var windowName = payload[1] + "\n" + payload[2];
			var index = windowNames.indexOf(windowName);
			if (index != -1) {
				windowNames.splice(index, 1);
				delete windowData[windowName];
				redrawWindowList();
				if (activeWindow != null && windowName == activeWindow[2]) {
					inputBoxModule.clearText();
					if (windowNames.length > 0)
						setActiveWindow(windowNames[Math.min(index, windowNames.length - 1)]);
					else
						removeChildren(messageListElem);
				}
			}
		} else if (type == "MARKREAD") {
			var windowName = payload[1] + "\n" + payload[2];
			var seq = payload[3];
			windowData[windowName].markedReadUntil = seq;
			if (activeWindow != null && windowName == activeWindow[2]) {
				var lines = windowData[windowName].lines;
				var rows = messageListElem.children;
				for (var i = 0; i < lines.length; i++) {
					var row = rows[i];
					var cl = row.classList;
					if (lines[i][0] < seq) {
						cl.add("read");
						cl.remove("unread");
					} else {
						cl.add("unread");
						cl.remove("read");
					}
				}
				activeWindowUpdated = true;
			}
		} else if (type == "CLEARLINES") {
			var windowName = payload[1] + "\n" + payload[2];
			var seq = payload[3];
			var lines = windowData[windowName].lines;
			var i;
			for (i = 0; i < lines.length && lines[i][0] < seq; i++);
			lines.splice(0, i);
			if (activeWindow != null && windowName == activeWindow[2]) {
				for (var j = 0; j < i; j++)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		} else if (type == "CONNECTED") {
			connectionData[payload[1]] = {
				currentNickname: null,
				channels: {},
			};
		}
	});
	
	if (activeWindowUpdated) {
		reflowMessagesTable();
		window.scrollTo(0, scrollToBottom ? document.documentElement.scrollHeight : scrollPosition);
	}
}


function openPrivateMessagingWindow(target, onerror) {
	var profile = activeWindow[0];
	var windowName = profile + "\n" + target;
	if (windowNames.indexOf(windowName) == -1)
		sendAction([["open-window", profile, target]], null, onerror);
	else {
		setActiveWindow(windowName);
		inputBoxModule.clearText();
	}
}



/*---- Input text box module ----*/

const inputBoxModule = new function() {
	// Elements
	const inputBoxElem                = elemId("input-box");
	const failedCommandsContainerElem = elemId("failed-commands-container");
	const failedCommandsElem          = elemId("failed-commands");
	
	// Variables
	var prevTabCompletion = null;  // Type tuple<int begin, int end, str prefix, str name> or null.
	
	// Constants
	const TAB_COMPLETION_REGEX = /^(|.* )([^ ]*)$/;
	// Type int. The default of 400 is a safe number to use, because an IRC protocol line is generally limited to 512 bytes, including prefix and parameters and newline.
	const maxBytesPerLine = 400;
	// A table of commands with regular structures (does not include all commands, such as /msg). Format per entry:
	// key is command name with slash, value is {minimum number of parameters, maximum number of parameters}.
	const OUTGOING_COMMAND_PARAM_COUNTS = {
		"/info"   : [0, 1],
		"/invite" : [2, 2],
		"/join"   : [1, 2],
		"/links"  : [0, 2],
		"/list"   : [0, 2],
		"/nick"   : [1, 1],
		"/part"   : [1, 1],
		"/stats"  : [0, 2],
		"/time"   : [0, 1],
		"/users"  : [0, 1],
		"/version": [0, 1],
		"/who"    : [0, 2],
		"/whois"  : [1, 2],
		"/whowas" : [1, 3],
	};
	
	// Initialization
	elemId("main").getElementsByTagName("form")[0].onsubmit = handleLine;
	inputBoxElem.oninput = colorizeLine;
	inputBoxElem.onblur = clearTabCompletion;
	inputBoxElem.onkeypress = function(ev) {
		if (ev.keyCode == 9) {
			doTabCompletion();
			return false;
		} else {
			clearTabCompletion();
			return true;
		}
	};
	inputBoxElem.value = "";
	
	removeChildren(failedCommandsElem);
	failedCommandsContainerElem.getElementsByTagName("a")[0].onclick = function() {
		failedCommandsContainerElem.style.display = "none";
		removeChildren(failedCommandsElem);
		return false;
	};
	
	// Private functions
	
	function handleLine() {
		var inputStr = inputBoxElem.value;
		if (activeWindow == null || inputStr == "")
			return false;
		var onerror = function() {
			failedCommandsContainerElem.style.removeProperty("display");
			var li = document.createElement("li");
			setElementText(li, inputStr);
			failedCommandsElem.appendChild(li);
		};
		
		if (!inputStr.startsWith("/") || inputStr.startsWith("//")) {  // Ordinary message
			if (inputStr.startsWith("//"))  // Ordinary message beginning with slash
				inputStr = inputStr.substring(1);
			sendMessage(activeWindow[0], activeWindow[1], inputStr, onerror);
			
		} else {  // Command or special message
			// The user input command is case-insensitive. The command sent to the server will be in uppercase.
			var parts = inputStr.split(" ");
			var cmd = parts[0].toLowerCase();
			
			// Irregular commands
			if (cmd == "/msg" && parts.length >= 3) {
				var profile = activeWindow[0];
				var party = parts[1];
				var windowName = profile + "\n" + party;
				var text = nthRemainingPart(inputStr, 2);
				if (windowNames.indexOf(windowName) == -1) {
					sendAction([["open-window", profile, party], ["send-line", profile, "PRIVMSG " + party + " :" + text]], null, onerror);
				} else {
					setActiveWindow(windowName);
					sendMessage(profile, party, text, onerror);
				}
			} else if (cmd == "/me" && parts.length >= 2) {
				sendMessage(activeWindow[0], activeWindow[1], "\u0001ACTION " + nthRemainingPart(inputStr, 1) + "\u0001", onerror);
			} else if (cmd == "/notice" && parts.length >= 3) {
				sendAction([["send-line", activeWindow[0], "NOTICE " + parts[1] + " :" + nthRemainingPart(inputStr, 2)]], null, onerror);
			} else if (cmd == "/part" && parts.length == 1) {
				sendAction([["send-line", activeWindow[0], "PART " + activeWindow[1]]], null, onerror);
			} else if (cmd == "/query" && parts.length == 2) {
				openPrivateMessagingWindow(parts[1], onerror);
			} else if (cmd == "/topic" && parts.length >= 2) {
				sendAction([["send-line", activeWindow[0], "TOPIC " + activeWindow[1] + " :" + nthRemainingPart(inputStr, 1)]], null, onerror);
			} else if (cmd == "/kick" && parts.length >= 2) {
				var reason = parts.length == 2 ? "" : nthRemainingPart(inputStr, 2);
				sendAction([["send-line", activeWindow[0], "KICK " + activeWindow[1] + " " + parts[1] + " :" + reason]], null, onerror);
			} else if (cmd == "/names" && parts.length == 1) {
				var params = activeWindow[1] != "" ? " " + activeWindow[1] : "";
				sendAction([["send-line", activeWindow[0], "NAMES" + params]], null, onerror);
			} else if (cmd in OUTGOING_COMMAND_PARAM_COUNTS) {
				// Regular commands
				var minMaxParams = OUTGOING_COMMAND_PARAM_COUNTS[cmd];
				var numParams = parts.length - 1;
				if (numParams >= minMaxParams[0] && numParams <= minMaxParams[1]) {
					var params = numParams > 0 ? " " + parts.slice(1).join(" ") : "";
					sendAction([["send-line", activeWindow[0], cmd.substring(1).toUpperCase() + params]], null, onerror);
				} else {
					alert("Invalid command");
					return false;  // Don't clear the text box
				}
			} else {
				alert("Invalid command");
				return false;  // Don't clear the text box
			}
		}
		inputBoxElem.value = "";
		return false;  // To prevent the form submitting
	}
	
	// Change classes of text box based on '/commands' and overlong text
	function colorizeLine() {
		var text = inputBoxElem.value;
		if (text.startsWith("/") && !text.startsWith("//"))
			inputBoxElem.classList.add("is-command");
		else
			inputBoxElem.classList.remove("is-command");
		
		var checktext;
		if (text.startsWith("//"))
			checktext = text.substring(1);
		else if (!text.startsWith("/"))
			checktext = text;
		else {  // Starts with '/' but not '//'
			var parts = text.split(" ");
			var cmd = parts[0].toLowerCase();
			if ((cmd == "/kick" || cmd == "/msg") && parts.length >= 3)
				checktext = nthRemainingPart(text, 2);
			else if ((cmd == "/me" || cmd == "/topic") && parts.length >= 2)
				checktext = nthRemainingPart(text, 1);
			else
				checktext = text;
		}
		if (countUtf8Bytes(checktext) > maxBytesPerLine)
			inputBoxElem.classList.add("is-overlong");
		else
			inputBoxElem.classList.remove("is-overlong");
	}
	
	function doTabCompletion() {
		do {  // Simulate goto
			if (document.activeElement != inputBoxElem)
				break;
			var index = inputBoxElem.selectionStart;
			if (index != inputBoxElem.selectionEnd)
				break;
			if (activeWindow == null)
				break;
			var profile = activeWindow[0];
			var party = activeWindow[1];
			if (!(profile in connectionData) || !(party in connectionData[profile].channels))
				break;
			
			var text = inputBoxElem.value;
			var match;
			var prefix;
			if (prevTabCompletion == null) {
				match = TAB_COMPLETION_REGEX.exec(text.substr(0, index));
				prefix = match[2].toLowerCase();
				if (prefix.length == 0)
					break;
			} else {
				match = null;
				prefix = prevTabCompletion[2];
			}
			
			var candidates = connectionData[profile].channels[party].members.filter(function(name) {
				return name.toLowerCase().startsWith(prefix); });
			if (candidates.length == 0)
				break;
			candidates.sort(function(s, t) {
				return s.toLowerCase().localeCompare(t.toLowerCase()); });
			
			var candidate;
			var beginning;
			if (prevTabCompletion == null) {
				candidate = candidates[0];
				beginning = match[1];
			} else {
				var oldcandidate = prevTabCompletion[3].toLowerCase();
				var i;  // Skip elements until one is strictly larger
				for (i = 0; i < candidates.length && candidates[i].toLowerCase() <= oldcandidate; i++);
				candidates.push(candidates[0]);  // Wrap-around
				candidate = candidates[i];
				beginning = text.substr(0, prevTabCompletion[0]);
			}
			var tabcomp = candidate;
			if (beginning.length == 0)
				tabcomp += ": ";
			else if (index < text.length)
				tabcomp += " ";
			inputBoxElem.value = beginning + tabcomp + text.substring(index);
			prevTabCompletion = [beginning.length, beginning.length + tabcomp.length, prefix, candidate];
			inputBoxElem.selectionStart = inputBoxElem.selectionEnd = prevTabCompletion[1];
			return;  // Don't clear the current tab completion
			
		} while (false);
		clearTabCompletion();
	}
	
	function clearTabCompletion() {
		prevTabCompletion = null;
	}
	
	// Exported members
	this.putText = function(s) {
		inputBoxElem.value = s;
		inputBoxElem.focus();
		inputBoxElem.selectionStart = inputBoxElem.selectionEnd = s.length;
	};
	this.clearText = function() {
		inputBoxElem.value = "";
	};
};



/*---- Context menu module ----*/

const menuModule = new function() {
	// Deletes the context menu <div> element, if one is present.
	function closeMenu() {
		var elem = elemId("menu");
		if (elem != null)
			elem.parentNode.removeChild(elem);
	}
	
	// Initialization
	htmlElem.onmousedown = closeMenu;
	
	// Exported members
	this.closeMenu = closeMenu;
	
	// 'items' has type list<pair<str text, func onclick/null>>. Returns an event handler function.
	this.makeOpener = function(items) {
		return function(ev) {
			closeMenu();
			var div = document.createElement("div");
			div.id = "menu";
			div.style.left = ev.pageX + "px";
			div.style.top  = ev.pageY + "px";
			var ul = document.createElement("ul");
			
			items.forEach(function(item) {
				var li = document.createElement("li");
				var child;
				if (item[1] == null) {
					child = document.createElement("span");
					child.className = "disabled";
				} else {
					child = document.createElement("a");
					child.href = "#";
					child.onclick = function() {
						closeMenu();
						item[1]();
						return false;
					};
				}
				setElementText(child, item[0]);
				li.appendChild(child);
				ul.appendChild(li);
			});
			
			div.appendChild(ul);
			div.onmousedown = function(evt) { evt.stopPropagation(); };
			document.getElementsByTagName("body")[0].appendChild(div);
			return false;
		};
	};
};



/*---- Networking functions ----*/

// Called after login (from authenticate()) and after a severe state desynchronization (indirectly from updateState()).
// This performs an Ajax request, changes the page layout, and renders the data on screen.
function getState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var data = JSON.parse(xhr.response);
		if (typeof data != "string") {  // Good data
			loadState(data);  // Process data and update UI
			updateState();  // Start polling
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		var li = document.createElement("li");
		setElementText(li, "(Unable to connect to data provider)");
		windowListElem.appendChild(li);
	};
	xhr.open("POST", "get-state.json", true);
	xhr.responseType = "text";
	xhr.timeout = 10000;
	xhr.send(JSON.stringify({"maxMessagesPerWindow":maxMessagesPerWindow}));
}


function updateState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		if (xhr.status != 200)
			xhr.onerror();
		else {
			var data = JSON.parse(xhr.response);
			if (data != null) {  // Success
				loadUpdates(data);
				retryTimeout = 1000;
				updateState();
			} else {  // Lost synchronization or fell behind too much; do full update and re-render text
				setTimeout(getState, retryTimeout);
				if (retryTimeout < 300000)
					retryTimeout *= 2;
			}
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		setTimeout(updateState, retryTimeout);
		if (retryTimeout < 300000)
			retryTimeout *= 2;
	};
	var maxWait = 60000;
	xhr.open("POST", "get-updates.json", true);
	xhr.responseType = "text";
	xhr.timeout = maxWait + 20000;
	xhr.send(JSON.stringify({"nextUpdateId":nextUpdateId, "maxWait":maxWait}));
}


// Type signature: str path, list<list<val>> payload, func onload/null, func ontimeout/null. Returns nothing.
function sendAction(payload, onload, ontimeout) {
	var xhr = new XMLHttpRequest();
	if (onload != null)
		xhr.onload = onload;
	if (ontimeout != null)
		xhr.ontimeout = ontimeout;
	xhr.open("POST", "do-actions.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"payload":payload}));
}


// Type signature: str profile, str target, str text. Returns nothing. The value (profile+"\n"+target) need not exist in windowNames.
function sendMessage(profile, target, text, onerror) {
	sendAction([["send-line", profile, "PRIVMSG " + target + " :" + text]], null, onerror);
}


/*---- Simple utility functions ----*/

function createBlankWindow() {
	return {
		lines: [],
		markedReadUntil: 0,
		numNewMessages: 0,
		isMuted: false,
	};
}


// Converts a Unix millisecond timestamp to a string, in the preferred format for lineDataToRowElem().
function formatDate(timestamp) {
	var d = new Date(timestamp);
	if (!optimizeMobile) {
		return twoDigits(d.getDate()) + "-" + DAYS_OF_WEEK[d.getDay()] + " " +
			twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes()) + ":" + twoDigits(d.getSeconds());
	} else {
		return DAYS_OF_WEEK[d.getDay()] + " " + twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes());
	}
}

var DAYS_OF_WEEK = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];


// Converts an integer to a two-digit string. For example, 0 -> "00", 9 -> "09", 23 -> "23".
function twoDigits(n) {
	return (n < 10 ? "0" : "") + n;
}


// Removes all children of the given DOM node.
function removeChildren(elem) {
	while (elem.firstChild != null)
		elem.removeChild(elem.firstChild);
}


// Removes all children of the given DOM node and adds a single text element containing the specified text.
function setElementText(elem, str) {
	removeChildren(elem);
	elem.appendChild(document.createTextNode(str));
}


// Finds the first n spaces in the string and returns the rest of the string after the last space found.
// For example: nthRemainingPart("a b c", 0) -> "a b c"; nthRemainingPart("a b c", 1) -> "b c"; nthRemainingPart("a b c", 3) -> exception.
function nthRemainingPart(s, n) {
	var j = 0;
	for (var i = 0; i < n; i++) {
		j = s.indexOf(" ", j);
		if (j == -1)
			throw "Space not found";
		j++;
	}
	return s.substring(j);
}


function countUtf8Bytes(s) {
	var result = 0;
	for (var i = 0; i < s.length; i++) {
		var c = s.charCodeAt(i);
		if (c < 0x80)
			result += 1;
		else if (c < 0x800)
			result += 2;
		else if (0xD800 <= c && c < 0xDC00 && i + 1 < s.length  // UTF-16 high and low surrogates
				&& 0xDC00 <= s.charCodeAt(i + 1) && s.charCodeAt(i + 1) < 0xE000) {
			result += 4;
			i++;
		} else
			result += 3;
	}
	return result;
}



/*---- Miscellaneous ----*/

// The call to init() must come last due to variables being declared and initialized.
init();
