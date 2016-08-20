/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.HashMap;
import java.util.Map;
import io.nayuki.mamirc.common.Event;


final class OfflineEventProcessor {
	
	public Map<Integer,IrcSession> sessions;
	
	private MessageSink msgSink;
	
	
	
	public OfflineEventProcessor(MessageSink msgSink) {
		sessions = new HashMap<>();
		this.msgSink = msgSink;
	}
	
	
	
	public void processEvent(Event ev) {
		if (ev == null)
			throw new NullPointerException();
		try {
			switch (ev.type) {
				case CONNECTION:
					processConnection(ev);
					break;
				case RECEIVE:
					processReceive(ev);
					break;
				case SEND:
					processSend(ev);
					break;
				default:
					throw new AssertionError();
			}
		} catch (IrcSyntaxException e) {}
	}
	
	
	private void processConnection(Event ev) {
		if (ev.type != Event.Type.CONNECTION)
			throw new IllegalArgumentException();
		final int conId = ev.connectionId;
		IrcSession session = sessions.get(conId);  // Possibly null
		final String line = ev.line.getString();
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			session = new IrcSession(profileName);
			sessions.put(conId, session);
			msgSink.addMessage(session, "", conId, ev, "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			session.setRegistrationState(IrcSession.RegState.OPENED);
			msgSink.addMessage(session, "", conId, ev, "OPENED", line.split(" ", 2)[1]);
			
		} else if (line.equals("disconnect")) {
			msgSink.addMessage(session, "", conId, ev, "DISCONNECT");
			
		} else if (line.equals("closed")) {
			msgSink.addMessage(session, "", conId, ev, "CLOSED");
			if (session != null)
				sessions.remove(conId);
			
		} else
			throw new AssertionError();
	}
	
	
	private void processReceive(Event ev) {
		if (ev.type != Event.Type.RECEIVE)
			throw new IllegalArgumentException();
		final int conId = ev.connectionId;
		final IrcSession session = sessions.get(conId);
		if (session == null)
			throw new AssertionError();
		final IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (session.getRegistrationState() != IrcSession.RegState.REGISTERED) {
					// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
					String feedbackNick = line.getParameter(0);
					if (session.getCurrentNickname().startsWith(feedbackNick))
						session.setNickname(feedbackNick);
					session.setRegistrationState(IrcSession.RegState.REGISTERED);
				}
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (session.getRegistrationState() != IrcSession.RegState.REGISTERED)
					session.moveNicknameToRejected();
				break;
			}
			
			case "NICK": {
				String fromname = line.prefixName;
				String toname   = line.getParameter(0);
				if (fromname.equals(session.getCurrentNickname())) {
					session.setNickname(toname);
					msgSink.addMessage(session, "", conId, ev, "NICK", fromname, toname);
				}
				for (Map.Entry<CaselessString,IrcSession.ChannelState> entry : session.getChannels().entrySet()) {
					IrcSession.ChannelState state = entry.getValue();
					if (state.members.contains(fromname)) {
						CaselessString chan = entry.getKey();
						session.partChannel(chan, fromname);
						session.joinChannel(chan, toname);
						msgSink.addMessage(session, chan.properCase, conId, ev, "NICK", fromname, toname);
					}
				}
				break;
			}
			
			case "PRIVMSG": {
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				msgSink.addMessage(session, party, conId, ev, "PRIVMSG", from, text);
				break;
			}
			
			case "NOTICE": {
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				msgSink.addMessage(session, party, conId, ev, "NOTICE", from, text);
				break;
			}
			
			case "JOIN": {
				String who  = line.prefixName;
				String user = line.prefixUsername;
				String host = line.prefixHostname;
				if (user == null)
					user = "";
				if (host == null)
					host = "";
				CaselessString chan = new CaselessString(line.getParameter(0));
				if (who.equals(session.getCurrentNickname()))
					session.joinChannel(chan);
				else
					session.joinChannel(chan, who);
				msgSink.addMessage(session, chan.properCase, conId, ev, "JOIN", who, user, host);
				break;
			}
			
			case "PART": {
				String who  = line.prefixName;
				CaselessString chan = new CaselessString(line.getParameter(0));
				if (who.equals(session.getCurrentNickname()))
					session.partChannel(chan);
				else
					session.partChannel(chan, who);
				msgSink.addMessage(session, chan.properCase, conId, ev, "PART", who);
				break;
			}
			
			case "KICK": {
				String from   = line.prefixName;
				CaselessString chan   = new CaselessString(line.getParameter(0));
				String target = line.getParameter(1);
				String reason = line.getParameter(2);
				if (target.equals(session.getCurrentNickname()))
					session.partChannel(chan);
				else
					session.partChannel(chan, target);
				msgSink.addMessage(session, chan.properCase, conId, ev, "KICK", from, target, reason);
				break;
			}
			
			case "QUIT": {
				String who    = line.prefixName;
				String reason = line.getParameter(0);
				for (Map.Entry<CaselessString,IrcSession.ChannelState> entry : session.getChannels().entrySet()) {
					IrcSession.ChannelState state = entry.getValue();
					if (state.members.contains(who)) {
						CaselessString chan = entry.getKey();
						session.partChannel(chan, who);
						msgSink.addMessage(session, chan.properCase, conId, ev, "QUIT", who, reason);
					}
				}
				break;
			}
			
			case "353": {  // RPL_NAMREPLY
				IrcSession.ChannelState channel = session.getChannels().get(line.getParameter(2));
				if (channel == null)
					break;
				if (!channel.isProcessingNamesReply) {
					channel.isProcessingNamesReply = true;
					channel.members.clear();
				}
				for (String name : line.getParameter(3).split(" ")) {
					char head = name.length() > 0 ? name.charAt(0) : '\0';
					if (head == '@' || head == '+' || head == '!' || head == '%' || head == '&' || head == '~')
						name = name.substring(1);
					channel.members.add(name);
				}
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (Map.Entry<CaselessString,IrcSession.ChannelState> entry : session.getChannels().entrySet()) {
					IrcSession.ChannelState channel = entry.getValue();
					if (channel.isProcessingNamesReply) {
						channel.isProcessingNamesReply = false;
						String[] names = channel.members.toArray(new String[0]);
						msgSink.addMessage(session, entry.getKey().properCase, conId, ev, "NAMES", names);
					}
				}
				break;
			}
			
			case "MODE": {
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = "";  // Assume it is a mode message for my user
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < line.parameters.size(); i++) {
					if (sb.length() > 0)
						sb.append(" ");
					sb.append(line.getParameter(i));
				}
				msgSink.addMessage(session, party, conId, ev, "MODE", from, sb.toString());
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
		
		// Show some types of server numeric replies
		if (line.command.matches("\\d{3}")) {
			switch (Integer.parseInt(line.command)) {
				case 331:
				case 332:
				case 333:
				case 353:
				case 366: {
					// Do nothing
					break;
				}
				
				default: {
					// Note: Parameter 0 should be my current nickname, which isn't very useful information
					StringBuilder sb = new StringBuilder();
					for (int i = 1; i < line.parameters.size(); i++) {
						if (sb.length() > 0)
							sb.append(" ");
						sb.append(line.getParameter(i));
					}
					msgSink.addMessage(session, "", conId, ev, "SERVRPL", sb.toString());
					break;
				}
			}
		}
	}
	
	
	private void processSend(Event ev) {
		if (ev.type != Event.Type.SEND)
			throw new IllegalArgumentException();
		final int conId = ev.connectionId;
		final IrcSession session = sessions.get(conId);
		if (session == null)
			throw new AssertionError();
		final IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "NICK": {
				if (session.getRegistrationState() == IrcSession.RegState.OPENED)
					session.setRegistrationState(IrcSession.RegState.NICK_SENT);
				if (session.getRegistrationState() != IrcSession.RegState.REGISTERED)
					session.setNickname(line.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (session.getRegistrationState() == IrcSession.RegState.NICK_SENT)
					session.setRegistrationState(IrcSession.RegState.USER_SENT);
				break;
			}
			
			case "PRIVMSG": {
				String from = session.getCurrentNickname();
				String party = line.getParameter(0);
				String text  = line.getParameter(1);
				msgSink.addMessage(session, party, conId, ev, "PRIVMSG+OUTGOING", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = session.getCurrentNickname();
				String party = line.getParameter(0);
				String text  = line.getParameter(1);
				msgSink.addMessage(session, party, conId, ev, "NOTICE+OUTGOING", from, text);
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	private static boolean isChannelName(String target) {
		if (target == null)
			throw new NullPointerException();
		return target.length() > 0 && (target.charAt(0) == '#' || target.charAt(0) == '&');
	}
	
}
