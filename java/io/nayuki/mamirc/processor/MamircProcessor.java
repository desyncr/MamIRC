/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.LockHelper;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.processor.IrcSession.RegState;
import io.nayuki.mamirc.processor.UserConfiguration.IrcNetwork;


/* 
 * The MamIRC processor main program class. The main thread creates a new MamircProcessor object, launches a handful
 * of worker threads, and returns. Thereafter, the MamircProcessor object holds the global state of the application,
 * always accessed from any one of the worker threads while the thread holds the lock.
 */
public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println("Usage: java io/nayuki/mamirc/processor/MamircProcessor BackendConfig.json UserConfig.json");
			System.exit(1);
		}
		
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);  // Prevent sqlite4java module from polluting stderr with debug messages
		new MamircProcessor(new BackendConfiguration(new File(args[0])), new File(args[1]));
		// The main thread returns, while other threads live on
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	// Current workers
	private ConnectorReaderThread reader;
	private OutputWriterThread writer;
	private MessageHttpServer server;
	private Timer timer;
	
	// Mutable current state
	private final Map<Integer,IrcSession> ircSessions;
	private AllWindows windows;
	private List<String> initialWindow;  // Either null or {String profile, String party}
	private final List<Object[]> recentUpdates;  // Payload is {int id, List<Object> update}
	private int nextUpdateId;
	private final Map<IrcNetwork,int[]> connectionAttemptState;  // Payload is {next server index, delay in milliseconds}
	private boolean isTerminating;
	private UserConfiguration userConfiguration;
	private final File userConfigurationFile;
	
	// Concurrency
	private final Lock lock;
	private final LockHelper locker;
	private final Condition condNewUpdates;
	private final Condition condTerminate;
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(BackendConfiguration backendConfig, File userConfigFile) throws IOException {
		if (backendConfig == null || userConfigFile == null)
			throw new NullPointerException();
		
		userConfigurationFile = userConfigFile;
		userConfiguration = new UserConfiguration(userConfigFile);
		ircSessions = new HashMap<>();
		windows = new AllWindows(this);
		initialWindow = null;
		recentUpdates = new ArrayList<>();
		nextUpdateId = 0;
		connectionAttemptState = new HashMap<>();
		isTerminating = false;
		lock = new ReentrantLock();
		locker = new LockHelper(lock);
		condNewUpdates = lock.newCondition();
		condTerminate = lock.newCondition();
		
		lock.lock();
		try {  // Do these things in mutex so another thread cannot call terminate() before initialization is complete
			// Initialize connection to Connector
			writer = null;
			reader = new ConnectorReaderThread(this, backendConfig);
			reader.start();
			// Initialize HTTP server
			server = new MessageHttpServer(this, backendConfig.webServerPort, backendConfig.webUiPassword);
			// Initialize asynchronous event executor
			timer = new Timer("MamircProcessor.timer");
		} catch (IOException e) {
			e.printStackTrace();
			terminate();
			return;
		} finally {
			lock.unlock();
		}
		
		// Refresh all channel names on all connections once a day
		timer.schedule(new TimerTask() {
			public void run() {
				try (LockHelper lh = locker.enter()) {
					for (Map.Entry<Integer,IrcSession> entry : ircSessions.entrySet()) {
						int conId = entry.getKey();
						for (String chan : entry.getValue().getCurrentChannels().keySet())
							sendIrcLine(conId, "NAMES", chan);
					}
				}
			}
		}, 86400000, 86400000);
	}
	
	
	
	/*---- Methods for manipulating global state ----*/
	
	public void processEvent(Event ev, boolean realtime) {
		if (ev == null)
			throw new NullPointerException();
		try (LockHelper lh = locker.enter()) {
			switch (ev.type) {
				case CONNECTION:
					processConnection(ev, realtime);
					break;
				case RECEIVE:
					processReceive(ev, realtime);
					break;
				case SEND:
					processSend(ev, realtime);
					break;
				default:
					throw new AssertionError();
			}
		} catch (IrcSyntaxException e) {
			e.printStackTrace();
		}
	}
	
	
	// Must only be called by processEvent().
	private void processConnection(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		IrcSession state = ircSessions.get(conId);  // Possibly null
		String line = ev.line.getString();
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String metadata = parts[4];
			if (!userConfiguration.ircNetworks.containsKey(metadata))
				throw new IllegalStateException("No profile: " + metadata);
			ircSessions.put(conId, new IrcSession(userConfiguration.ircNetworks.get(metadata)));
			windows.addConnectingLine(metadata, ev.timestamp, parts[1], Integer.parseInt(parts[2]), parts[3].equals("ssl"));
			
		} else if (line.startsWith("opened ")) {
			state.setRegistrationState(RegState.OPENED);
			if (realtime)
				sendIrcLine(conId, "NICK", state.profile.nicknames.get(0));
			addUpdate("CONNECTED", state.profile.name);
			windows.addConnectedLine(state.profile.name, ev.timestamp, line.split(" ", 2)[1]);
			
		} else if (line.equals("disconnect")) {
			// Do nothing
			
		} else if (line.equals("closed")) {
			if (state != null) {
				for (String chan : state.getCurrentChannels().keySet())
					windows.addDisconnectedLine(state.profile.name, chan, ev.timestamp);
				windows.addDisconnectedLine(state.profile.name, "", ev.timestamp);
				ircSessions.remove(conId);
				IrcNetwork profile = userConfiguration.ircNetworks.get(state.profile.name);
				if (profile != null && profile.connect)
					tryConnect(profile);
			}
		}
	}
	
	
	// Must only be called by processEvent().
	private void processReceive(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		IrcSession state = ircSessions.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(ev.line.getString());
		Map<String,IrcSession.ChannelState> curchans = state.getCurrentChannels();
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				String fromname = msg.prefixName;
				String toname = msg.getParameter(0);
				if (fromname.equals(state.getCurrentNickname())) {
					state.setNickname(toname);
					addUpdate("MYNICK", state.profile.name, toname);
				}
				for (Map.Entry<String,IrcSession.ChannelState> entry : curchans.entrySet()) {
					Set<String> members = entry.getValue().members;
					if (members.remove(fromname)) {
						members.add(toname);
						windows.addNickLine(profile.name, entry.getKey(), ev.timestamp, fromname, toname);
					}
				}
				break;
			}
			
			case "JOIN": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (who.equals(state.getCurrentNickname()) && !curchans.containsKey(chan)) {
					curchans.put(chan, new IrcSession.ChannelState());
					addUpdate("JOINED", state.profile.name, chan);
				}
				if (curchans.containsKey(chan) && curchans.get(chan).members.add(who))
					windows.addJoinLine(profile.name, msg.getParameter(0), ev.timestamp, who);
				break;
			}
			
			case "NOTICE": {
				String who = msg.prefixName;
				String party = msg.getParameter(0);
				if (party.equals(state.getCurrentNickname()))
					party = who;
				windows.addNoticeLine(profile.name, party, 0, ev.timestamp, who, msg.getParameter(1));
				break;
			}
			
			case "PART": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (curchans.containsKey(chan) && curchans.get(chan).members.remove(who))
					windows.addPartLine(profile.name, chan, ev.timestamp, who);
				if (who.equals(state.getCurrentNickname())) {
					curchans.remove(chan);
					addUpdate("PARTED", state.profile.name, chan);
				}
				break;
			}
			
			case "KICK": {
				String reason = msg.getParameter(2);
				for (String chan : msg.getParameter(0).split(",")) {
					boolean mekicked = false;
					for (String party : msg.getParameter(1).split(",")) {
						if (curchans.containsKey(chan) && curchans.get(chan).members.contains(party)) {
							if (party.equalsIgnoreCase(state.getCurrentNickname()))
								mekicked = true;
							else
								windows.addKickLine(profile.name, chan, ev.timestamp, party, msg.prefixName, reason);
							curchans.get(chan).members.remove(party);
						}
					}
					if (mekicked) {  // Save this part for last
						windows.addKickLine(profile.name, chan, ev.timestamp, state.getCurrentNickname(), msg.prefixName, reason);
						addUpdate("KICKED", state.profile.name, chan, msg.prefixName, reason);
						curchans.remove(chan);
					}
				}
				break;
			}
			
			case "MODE": {
				String party = msg.getParameter(0);
				if (!state.getCurrentChannels().containsKey(party))
					break;
				String text = "";
				for (int i = 1; i < msg.parameters.size(); i++) {
					if (text.length() > 0)
						text += " ";
					text += msg.getParameter(i);
				}
				windows.addModeLine(profile.name, party, ev.timestamp, msg.prefixName, text);
				break;
			}
			
			case "PRIVMSG": {
				String who = msg.prefixName;
				String party = msg.getParameter(0);
				if (party.charAt(0) != '#' && party.charAt(0) != '&')  // Not a channel, and is therefore a private message to me
					party = who;
				String text = msg.getParameter(1);
				int flags = 0;
				if (state.getNickflagDetector().matcher(text).find())
					flags |= Window.Flags.NICKFLAG.value;
				windows.addPrivmsgLine(profile.name, party, flags, ev.timestamp, who, text);
				break;
			}
			
			case "QUIT": {
				String who = msg.prefixName;
				if (!who.equals(state.getCurrentNickname())) {
					for (Map.Entry<String,IrcSession.ChannelState> entry : curchans.entrySet()) {
						if (entry.getValue().members.remove(who))
							windows.addQuitLine(profile.name, entry.getKey(), ev.timestamp, who, msg.getParameter(0));
					}
				} else {
					addUpdate("QUITTED", state.profile.name);
				}
				break;
			}
			
			case "TOPIC": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				String text = msg.getParameter(1);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = text;
				windows.addTopicLine(profile.name, chan, ev.timestamp, who, text);
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (state.getRegistrationState() != RegState.REGISTERED) {
					state.moveNicknameToRejected();
					if (realtime) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!state.isNicknameRejected(nickname)) {
								sendIrcLine(conId, "NICK", nickname);
								found = true;
								break;
							}
						}
						if (!found)
							sendDisconnect(conId, false);
					} else
						state.setNickname(null);
				}
				break;
			}
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (state.getRegistrationState() != RegState.REGISTERED) {
					// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
					String feedbackNick = msg.getParameter(0);
					if (state.getCurrentNickname().startsWith(feedbackNick))
						state.setNickname(feedbackNick);
					
					state.setRegistrationState(RegState.REGISTERED);
					if (realtime) {
						if (profile.nickservPassword != null && !state.getSentNickservPassword())
							sendIrcLine(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
						joinChannelsByProfile(conId);
					}
					addUpdate("MYNICK", profile.name, state.getCurrentNickname());
					connectionAttemptState.remove(state.profile);
				}
				break;
			}
			
			case "331": {  // RPL_NOTOPIC
				String chan = msg.getParameter(1);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = null;
				windows.addInitNoTopicLine(profile.name, chan, ev.timestamp);
				break;
			}
			
			case "332": {  // RPL_TOPIC
				String chan = msg.getParameter(1);
				String text = msg.getParameter(2);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = text;
				windows.addInitTopicLine(profile.name, chan, ev.timestamp, text);
				break;
			}
			
			case "353": {  // RPL_NAMREPLY
				String chan = msg.getParameter(2);
				if (curchans.containsKey(chan)) {
					IrcSession.ChannelState chanstate = curchans.get(chan);
					if (!chanstate.processingNamesReply) {
						chanstate.members.clear();
						chanstate.processingNamesReply = true;
					}
					for (String name : msg.getParameter(3).split(" ")) {
						if (name.startsWith("@") || name.startsWith("+") || name.startsWith("!") || name.startsWith("%"))
							name = name.substring(1);
						chanstate.members.add(name);
					}
				}
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (Map.Entry<String,IrcSession.ChannelState> entry : curchans.entrySet()) {
					IrcSession.ChannelState chanstate = entry.getValue();
					if (chanstate.processingNamesReply) {
						chanstate.processingNamesReply = false;
						String[] names = chanstate.members.toArray(new String[0]);
						windows.addNamesLine(profile.name, entry.getKey(), ev.timestamp, names);
					}
				}
				break;
			}
			
			default:
				break;  // Ignore event
		}
		
		// Relay some types of numeric replies to the client
		if (msg.command.matches("\\d{3}")) {
			switch (Integer.parseInt(msg.command)) {
				case 331:
				case 332:
				case 333:
				case 353:
				case 366: {
					// Do nothing
					break;
				}
				
				default: {
					if (msg.command.equals("433") && state.getRegistrationState() != RegState.REGISTERED)
						break;  // Suppress nickname conflict notices during registration
					
					// Note: Parameter 0 should be my current nickname, which isn't very useful information
					String text = "";
					for (int i = 1; i < msg.parameters.size(); i++) {
						if (text.length() > 0)
							text += " ";
						text += msg.getParameter(i);
					}
					windows.addServerReplyLine(profile.name, ev.timestamp, msg.command, text);
					break;
				}
			}
		}
	}
	
	
	// Must only be called by processEvent().
	private void processSend(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		IrcSession state = ircSessions.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(ev.line.getString());
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				if (state.getRegistrationState() == RegState.OPENED) {
					state.setRegistrationState(RegState.NICK_SENT);
					if (realtime)
						sendIrcLine(conId, "USER", profile.username, "0", "*", profile.realname);
				}
				if (state.getRegistrationState() != RegState.REGISTERED)
					state.setNickname(msg.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (state.getRegistrationState() == RegState.NICK_SENT)
					state.setRegistrationState(RegState.USER_SENT);
				break;
			}
			
			case "NOTICE": {
				String src = state.getCurrentNickname();
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				windows.addNoticeLine(profile.name, party, Window.Flags.OUTGOING.value, ev.timestamp, src, text);
				break;
			}
			
			case "PRIVMSG": {
				if (msg.parameters.size() == 2 && msg.getParameter(0).equals("NickServ") && msg.getParameter(1).toUpperCase().startsWith("IDENTIFY "))
					state.setSentNickservPassword();
				String src = state.getCurrentNickname();
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				windows.addPrivmsgLine(profile.name, party, Window.Flags.OUTGOING.value, ev.timestamp, src, text);
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	// Must only be called from ConnectorReaderThread, and only called once.
	public void finishCatchup() {
		try (LockHelper lh = locker.enter()) {
			Set<IrcNetwork> activeProfiles = new HashSet<>();
			for (int conId : ircSessions.keySet()) {
				IrcSession state = ircSessions.get(conId);
				IrcNetwork profile = state.profile;
				if (!activeProfiles.add(profile))
					throw new IllegalStateException("Multiple active connections for profile: " + profile.name);
				switch (state.getRegistrationState()) {
					case CONNECTING:
						break;
						
					case OPENED: {
						sendIrcLine(conId, "NICK", profile.nicknames.get(0));
						break;
					}
					
					case NICK_SENT:
					case USER_SENT: {
						if (state.getCurrentNickname() == null) {
							boolean found = false;
							for (String nickname : profile.nicknames) {
								if (!state.isNicknameRejected(nickname)) {
									sendIrcLine(conId, "NICK", nickname);
									found = true;
									break;
								}
							}
							if (!found)
								sendDisconnect(conId, false);
						} else if (state.getRegistrationState() == RegState.NICK_SENT)
							sendIrcLine(conId, "USER", profile.username, "0", "*", profile.realname);
						break;
					}
					
					case REGISTERED: {
						if (profile.nickservPassword != null && !state.getSentNickservPassword())
							sendIrcLine(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
						joinChannelsByProfile(conId);
						break;
					}
					
					default:
						throw new AssertionError();
				}
			}
			
			// Connect to networks
			for (IrcNetwork net : userConfiguration.ircNetworks.values()) {
				if (net.connect && !activeProfiles.contains(net))
					tryConnect(net);
			}
		}
	}
	
	
	// Must be called from one of the locking methods above.
	private void tryConnect(final IrcNetwork net) {
		int delay;
		if (!connectionAttemptState.containsKey(net)) {
			connectionAttemptState.put(net, new int[]{0, 1000});
			delay = 0;
		} else
			delay = connectionAttemptState.get(net)[1];
		
		timer.schedule(new TimerTask() {
			public void run() {
				try (LockHelper lh = locker.enter()) {
					if (isTerminating)
						return;
					for (IrcSession state : ircSessions.values()) {
						if (state.profile.name.equals(net.name))
							break;
					}
					
					int[] attemptState = connectionAttemptState.get(net);
					IrcNetwork.Server serv = net.servers.get(attemptState[0]);
					String str = "connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + net.name;
					writer.postWrite(new CleanLine(str));
					
					if (1000 < attemptState[1] && attemptState[1] < 200000)
						attemptState[1] *= 2;  // Exponential backoff
					attemptState[0]++;
					if (attemptState[0] == net.servers.size()) {
						attemptState[0] = 0;
						if (attemptState[1] == 1000)
							attemptState[1] *= 2;
					}
				}
			}
		}, delay);
	}
	
	
	// Must be called from one of the locking methods.
	private void sendIrcLine(int conId, String cmd, String... params) {
		StringBuilder sb = new StringBuilder("send ").append(conId).append(' ').append(cmd);
		for (int i = 0; i < params.length; i++) {
			sb.append(' ');
			if (i == params.length - 1)
				sb.append(':');
			sb.append(params[i]);
		}
		writer.postWrite(sb.toString());
	}
	
	
	// Must be called from one of the locking methods.
	private void joinChannelsByProfile(int conId) {
		IrcSession state = ircSessions.get(conId);
		IrcNetwork profile = state.profile;
		for (String chanStr : profile.channels) {
			String[] parts = chanStr.split(" ", 2);  // Optional second part is the channel key
			if (!state.getCurrentChannels().containsKey(parts[0]))
				sendIrcLine(conId, "JOIN", parts);
		}
	}
	
	
	// Must be called from one of the locking methods.
	private void sendDisconnect(final int conId, boolean sendQuit) {
		if (!sendQuit)
			writer.postWrite("disconnect " + conId);
		else {
			// Send QUIT line, delay a bit for the write to flush, then
			// close connection forcefully if server hasn't done so already
			sendIrcLine(conId, "QUIT", "MamIRC, the headless IRC client");
			timer.schedule(new TimerTask() {
				public void run() {
					try (LockHelper lh = locker.enter()) {
						if (ircSessions.containsKey(conId))
							sendDisconnect(conId, false);
					}
				}
			}, 1000);
		}
	}
	
	
	// Must only be called from ConnectorReaderThread, and only called once.
	public void attachConnectorWriter(OutputWriterThread writer) {
		try (LockHelper lh = locker.enter()) {
			if (this.writer != null)
				throw new IllegalStateException();
			this.writer = writer;
		}
	}
	
	
	public void terminate() {
		try (LockHelper lh = locker.enter()) {
			if (reader != null)
				reader.terminate();
			if (server != null)
				server.terminate();
			if (timer != null)
				timer.cancel();
			isTerminating = true;
			condTerminate.signalAll();
			condNewUpdates.signalAll();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	// Must be called from one of the locking methods.
	private void addUpdate(Object... update) {
		if (update == null)
			throw new NullPointerException();
		
		// Store the update
		recentUpdates.add(new Object[]{nextUpdateId, Arrays.asList(update)});
		nextUpdateId++;
		
		// Clean up the list if it gets too big
		if (recentUpdates.size() > 10000)
			recentUpdates.subList(0, recentUpdates.size() / 2).clear();
		
		// Unblock any currently waiting server threads
		condNewUpdates.signalAll();
	}
	
	
	// Must be called in a locked context, and only be called by AllWindows.
	public void addWindowUpdate(String profile, String party, int sequence, int flags, long timestamp, Object... payload) {
		Object[] temp = new Object[6 + payload.length];
		temp[0] = "APPEND";
		temp[1] = profile;
		temp[2] = party;
		temp[3] = sequence;
		temp[4] = flags;
		temp[5] = timestamp;
		System.arraycopy(payload, 0, temp, 6, payload.length);
		addUpdate(temp);
	}
	
	
	
	/*---- HTTP web API ----*/
	
	// The methods below should only be called from MessageHttpServer.
	
	public Map<String,Object> getState(int maxMsgPerWin) {
		try (LockHelper lh = locker.enter()) {
			Map<String,Object> result = new HashMap<>();
			
			// States of current connections
			Map<String,Map<String,Object>> outConnections = new HashMap<>();
			for (Map.Entry<Integer,IrcSession> conEntry : ircSessions.entrySet()) {
				IrcSession inConState = conEntry.getValue();
				Map<String,Object> outConState = new HashMap<>();
				outConState.put("currentNickname", inConState.getCurrentNickname());
				
				Map<String,IrcSession.ChannelState> inChannels = inConState.getCurrentChannels();
				Map<String,Map<String,Object>> outChannels = new HashMap<>();
				for (Map.Entry<String,IrcSession.ChannelState> chanEntry : inChannels.entrySet()) {
					Map<String,Object> outChanState = new HashMap<>();
					outChanState.put("members", new ArrayList<>(chanEntry.getValue().members));
					outChanState.put("topic", chanEntry.getValue().topic);
					outChannels.put(chanEntry.getKey(), outChanState);
				}
				outConState.put("channels", outChannels);
				
				outConnections.put(inConState.profile.name, outConState);
			}
			result.put("connections", outConnections);
			
			// States of current windows
			List<List<Object>> outWindows = new ArrayList<>();
			for (Map.Entry<String,Map<String,Window>> profileEntry : windows.windows.entrySet()) {
				for (Map.Entry<String,Window> partyEntry : profileEntry.getValue().entrySet()) {
					List<Object> outWindow = new ArrayList<>();
					outWindow.add(profileEntry.getKey());
					outWindow.add(partyEntry.getKey());
					
					Window inWindow = partyEntry.getValue();
					List<List<Object>> outLines = new ArrayList<>();
					long prevTimestamp = 0;
					List<Window.Line> inLines = inWindow.lines;
					inLines = inLines.subList(Math.max(inLines.size() - maxMsgPerWin, 0), inLines.size());
					for (Window.Line line : inLines) {
						List<Object> lst = new ArrayList<>();
						lst.add(line.sequence);
						lst.add(line.flags);
						lst.add(line.timestamp - prevTimestamp);  // Delta encoding
						prevTimestamp = line.timestamp;
						Collections.addAll(lst, line.payload);
						outLines.add(lst);
					}
					
					Map<String,Object> outWinState = new HashMap<>();
					outWinState.put("lines", outLines);
					outWinState.put("markedReadUntil", inWindow.markedReadUntil);
					outWindow.add(outWinState);
					outWindows.add(outWindow);
				}
			}
			result.put("windows", outWindows);
			
			// Miscellaneous
			result.put("nextUpdateId", nextUpdateId);
			Map<String,Integer> flagConst = new HashMap<>();
			for (Window.Flags flag : Window.Flags.values())
				flagConst.put(flag.name(), flag.value);
			result.put("flagsConstants", flagConst);
			result.put("initialWindow", initialWindow);
			result.put("userConfiguration", userConfiguration.toJsonObject());
			return result;
		}
	}
	
	
	// Returns a JSON object containing updates with id >= startId (the list might be empty),
	// or null to indicate that the request is invalid and the client must request the full state.
	@SuppressWarnings("unchecked")
	public Map<String,Object> getUpdates(int startId, int maxWait) {
		if (maxWait < 0)
			throw new IllegalArgumentException();
		try (LockHelper lh = locker.enter()) {
			if (startId < 0 || startId > nextUpdateId)
				return null;
			
			int i = recentUpdates.size();
			while (i >= 1 && (Integer)recentUpdates.get(i - 1)[0] >= startId)
				i--;
			
			if (i == recentUpdates.size() && maxWait > 0) {  // Result currently empty, wait for more
				try {
					condNewUpdates.await(maxWait, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {}
				return getUpdates(startId, 0);
			} else if (nextUpdateId == 0 || i > 0) {  // Valid result to return, possibly empty
				Map<String,Object> result = new HashMap<>();
				List<List<Object>> updates = new ArrayList<>();
				while (i < recentUpdates.size()) {
					updates.add((List<Object>)recentUpdates.get(i)[1]);
					i++;
				}
				result.put("updates", updates);
				result.put("nextUpdateId", nextUpdateId);
				return result;
			} else  // No overlap
				return null;  // Tell the client to resynchronize
		}
	}
	
	
	public Map<String,Object> getProfiles() {
		try (LockHelper lh = locker.enter()) {
			Map<String,Object> result = new HashMap<>();
			for (Entry<String,IrcNetwork> entry : userConfiguration.ircNetworks.entrySet()) {
				IrcNetwork inProfile = entry.getValue();
				Map<String,Object> outProfile = new HashMap<>();
				outProfile.put("connect", inProfile.connect);
				outProfile.put("nicknames", inProfile.nicknames);
				outProfile.put("username", inProfile.username);
				outProfile.put("realname", inProfile.realname);
				outProfile.put("nickservPassword", inProfile.nickservPassword);
				outProfile.put("channels", new ArrayList<>(inProfile.channels));
				List<Map<String,Object>> outServers = new ArrayList<>();
				for (IrcNetwork.Server inServer : inProfile.servers) {
					Map<String,Object> outServer = new HashMap<>();
					outServer.put("hostname", inServer.hostnamePort.getHostString());
					outServer.put("port", inServer.hostnamePort.getPort());
					outServer.put("ssl", inServer.useSsl);
					outServers.add(outServer);
				}
				outProfile.put("servers", outServers);
				result.put(entry.getKey(), outProfile);
			}
			return result;
		}
	}
	
	
	public void setProfiles(Map<String,IrcNetwork> newProfiles) throws IOException {
		try (LockHelper lh = locker.enter()) {
			userConfiguration.ircNetworks = newProfiles;
			userConfiguration.writeToFile(userConfigurationFile);
			
			// Manipulate existing connections
			Set<String> activeProfileNames = new HashSet<>();
			for (Map.Entry<Integer,IrcSession> entry : ircSessions.entrySet()) {
				final Integer conId = entry.getKey();
				IrcSession session = entry.getValue();
				String name = session.profile.name;
				IrcNetwork profile = newProfiles.get(name);
				if (profile == null || !profile.connect)
					sendDisconnect(conId, true);
				else {
					activeProfileNames.add(name);
					IrcSession state = ircSessions.get(conId);
					if (state.getRegistrationState() == RegState.REGISTERED)
						joinChannelsByProfile(conId);
				}
			}
			
			// Make new connections
			for (Map.Entry<String,IrcNetwork> entry : newProfiles.entrySet()) {
				String name = entry.getKey();
				IrcNetwork profile = entry.getValue();
				if (profile.connect && !activeProfileNames.contains(name))
					tryConnect(profile);
			}
		}
	}
	
	
	public boolean sendLine(String profile, String line) {
		try (LockHelper lh = locker.enter()) {
			IrcSession session = null;
			int conId = -1;
			for (Map.Entry<Integer,IrcSession> entry : ircSessions.entrySet()) {
				if (entry.getValue().profile.name.equals(profile)) {
					conId = entry.getKey();
					session = entry.getValue();
					break;
				}
			}
			if (session == null)
				return false;
			session.handleThrottledSendLine("send " + conId + " " + line, timer, lock, writer);
			return true;
		}
	}
	
	
	public void markRead(String profile, String party, int sequence) {
		try (LockHelper lh = locker.enter()) {
			windows.windows.get(profile).get(party).markedReadUntil = sequence;
			addUpdate("MARKREAD", profile, party, sequence);
		}
	}
	
	
	public void clearLines(String profile, String party, int sequence) {
		try (LockHelper lh = locker.enter()) {
			windows.getWindow(profile, party).clearUntil(sequence);
			addUpdate("CLEARLINES", profile, party, sequence);
		}
	}
	
	
	public void openWindow(String profile, String party) {
		try (LockHelper lh = locker.enter()) {
			if (windows.openWindow(profile, party))
				addUpdate("OPENWIN", profile, party);
		}
	}
	
	
	public void closeWindow(String profile, String party) {
		try (LockHelper lh = locker.enter()) {
			if (windows.closeWindow(profile, party))
				addUpdate("CLOSEWIN", profile, party);
		}
	}
	
	
	public void setInitialWindow(String profile, String party) {
		try (LockHelper lh = locker.enter()) {
			initialWindow = Arrays.asList(profile, party);
		}
	}
	
	
	public int getNextUpdateId() {
		try (LockHelper lh = locker.enter()) {
			return nextUpdateId;
		}
	}
	
}
