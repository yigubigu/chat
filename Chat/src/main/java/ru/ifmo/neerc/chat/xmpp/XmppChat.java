package ru.ifmo.neerc.chat.xmpp;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.PacketIDFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.filter.PacketExtensionFilter;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.packet.DelayInformation;
import org.jivesoftware.smackx.packet.MUCUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.neerc.chat.client.Chat;
import ru.ifmo.neerc.chat.message.Message;
import ru.ifmo.neerc.chat.message.UserMessage;
import ru.ifmo.neerc.chat.user.UserEntry;
import ru.ifmo.neerc.chat.user.UserRegistry;
import ru.ifmo.neerc.chat.utils.DebugUtils;
import ru.ifmo.neerc.chat.xmpp.provider.*;
import ru.ifmo.neerc.chat.xmpp.packet.*;
import ru.ifmo.neerc.task.Task;
import ru.ifmo.neerc.task.TaskStatus;
import ru.ifmo.neerc.task.TaskRegistry;
import ru.ifmo.neerc.utils.XmlUtils;

import java.util.Calendar;
import java.util.Date;

/**
 * @author Evgeny Mandrikov
 */
public class XmppChat implements Chat {
    private static final Logger LOG = LoggerFactory.getLogger(XmppChat.class);

    private static final String SERVER_HOST = System.getProperty("server.host", "localhost");
    private static final String SERVER_HOSTNAME = System.getProperty("server.hostname", SERVER_HOST);
    private static final int SERVER_PORT = Integer.parseInt(System.getProperty("server.port", "5222"));
    private static final String ROOM = "neerc@conference." + SERVER_HOSTNAME;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("smack.debug", "false"));
	private static final String NEERC_SERVICE = "neerc." + SERVER_HOSTNAME;

    private MultiUserChat muc;
    private XMPPConnection connection;
    private boolean connected;
    
    private String name;
    private String password = System.getProperty("password", "12345");

    private MUCListener mucListener;
    private Date lastActivity = null;

    public XmppChat(
            String name,
            MUCListener mucListener
    ) {
        this.name = name;
        this.mucListener = mucListener;

        NeercTaskPacketExtensionProvider.register();
        NeercClockPacketExtensionProvider.register();
        NeercIQProvider.register();
        SASLAuthentication.supportSASLMechanism("PLAIN", 0);
    }

    public synchronized void disconnect() {
        connected = false;
        if (connection != null) {
            connection.disconnect();
        }
    }
    
    public synchronized void connect() {
        disconnect();
        LOG.info("connecting to server");
        // Create the configuration for this new connection
        ConnectionConfiguration config = new ConnectionConfiguration(SERVER_HOST, SERVER_PORT);
        config.setCompressionEnabled(true);
        config.setSASLAuthenticationEnabled(true);
        config.setDebuggerEnabled(DEBUG);

        connection = new XMPPConnection(config);
        // Connect to the server
        try {
            connection.connect();
            authenticate();
        } catch (XMPPException e) {
            LOG.error("Unable to connect", e);
            throw new RuntimeException(e);
        }

        // Create a MultiUserChat using an XMPPConnection for a room
        muc = new MultiUserChat(connection, ROOM);
        muc.addMessageListener(new MyMessageListener());

        connection.addPacketListener(new MyPresenceListener(), new PacketTypeFilter(Presence.class));
        connection.addPacketListener(new TaskPacketListener(), new PacketExtensionFilter("x", XmlUtils.NAMESPACE_TASKS));

        join();

        debugConnection();

        connected = true;
        mucListener.connected(this);
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    private void authenticate() throws XMPPException {
        connection.login(name, password, connection.getHost());
    }

    private void join() {
        try {
            // Joins the new room and retrieves history
            DiscussionHistory history = new DiscussionHistory();
            if (lastActivity != null) {
                history.setSince(new Date(lastActivity.getTime() + 1));
            } else {
                if (System.getProperty("history") != null) {
                    int size = Integer.parseInt(System.getProperty("history"));
                    history.setMaxStanzas(size);
                } else {
                    Calendar calendar = Calendar.getInstance();
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    history.setSince(calendar.getTime());
                }
            }
            muc.join(
                    name, // nick
                    "",   // password
                    history,
                    SmackConfiguration.getPacketReplyTimeout()
            );
        } catch (XMPPException e) {
            LOG.error("Unable to join room", e);
        }

        try {
            queryUsers();
            queryTasks();
        } catch (XMPPException e) {
            LOG.error("Unable to communicate with NEERC service", e);
        }
    }

    public void debugConnection() {
        LOG.debug("User: {}", connection.getUser());
        LOG.debug("Connected: {}", connection.isConnected());
        LOG.debug("Authenticated: {}", connection.isAuthenticated());
        LOG.debug("Joined: {}", muc.isJoined());
    }

    @Override
    public void write(Message message) {
        try {
            if (message instanceof UserMessage) {
                UserMessage userMessage = (UserMessage) message;
                muc.sendMessage(userMessage.getText());
            } else {
                throw new UnsupportedOperationException(message.getClass().getSimpleName());
            }
        } catch (XMPPException e) {
            LOG.error("Unable to write message", e);
        }
    }
    
	@Override
	public void write(Task task) {
        if (task.getScheduleType() == Task.ScheduleType.NONE) {
            NeercTaskIQ packet = new NeercTaskIQ(task);
            packet.setTo(NEERC_SERVICE);
            connection.sendPacket(packet);
        }
        else
            TaskRegistry.getInstance().update(task);
    }

	@Override
	public void write(Task task, TaskStatus status) {
		NeercTaskResultIQ packet = new NeercTaskResultIQ(task, status);
		packet.setTo(NEERC_SERVICE);
		connection.sendPacket(packet);
    }

	public IQ query(String what) throws XMPPException {
		Packet packet = new NeercIQ(what);
		packet.setTo(NEERC_SERVICE);
		
		PacketCollector collector = connection.createPacketCollector(
			new PacketIDFilter(packet.getPacketID()));
		connection.sendPacket(packet);

		IQ response = (IQ)collector.nextResult(SmackConfiguration.getPacketReplyTimeout());
		collector.cancel();
		if (response == null) {
			throw new XMPPException("No response from the server.");
		} else if (response.getType() == IQ.Type.ERROR) {
			throw new XMPPException(response.getError());
		}
//		LOG.debug("parsed " + response.getClass().getName());
		return response;
    }
	
	public void queryUsers() throws XMPPException {
		IQ iq = query("users");
		if (!(iq instanceof NeercUserListIQ)) {
		    throw new XMPPException("unparsed iq packet");
		}
		NeercUserListIQ packet = (NeercUserListIQ) iq;
        UserRegistry registry = UserRegistry.getInstance();
		for (UserEntry user: packet.getUsers()) {
		    // TODO: replace with registry.add(UserEntry user)
            UserEntry reguser = registry.findOrRegister(user.getName());
            reguser.setPower(user.isPower());
            reguser.setGroup(user.getGroup());
		}
	}

	public void queryTasks() throws XMPPException {
		IQ iq = query("tasks");
		if (!(iq instanceof NeercTaskListIQ)) {
		    throw new XMPPException("unparsed iq packet");
		}
		NeercTaskListIQ packet = (NeercTaskListIQ) iq;
		TaskRegistry.getInstance().reset();
		for (Task task: packet.getTasks()) {
			TaskRegistry.getInstance().update(task);
		}
	}


    public MultiUserChat getMultiUserChat() {
        return muc;
    }

    public XMPPConnection getConnection() {
        return connection;
    }

    private class MyPresenceListener implements PacketListener {
        public void processPacket(Packet packet) {
            if (!(packet instanceof Presence)) {
                return;
            }
            Presence presence = (Presence) packet;
            // Filter presence by room name
            final String from = presence.getFrom();
            if (!from.startsWith(ROOM)) {
                return;
            }
            final MUCUser mucExtension = (MUCUser) packet.getExtension("x", "http://jabber.org/protocol/muc#user");
            if (mucExtension != null) {
                MUCUser.Item item = mucExtension.getItem();
                LOG.debug(from + " " + DebugUtils.userItemToString(item));
                mucListener.roleChanged(from, item.getRole());
            }
            if (presence.isAvailable()) {
                mucListener.joined(from);
            } else {
                mucListener.left(from);
            }
        }
    }

    private class MyMessageListener implements PacketListener {
        @Override
        public void processPacket(Packet packet) {
            if (!(packet instanceof org.jivesoftware.smack.packet.Message)) {
                return;
            }

            org.jivesoftware.smack.packet.Message xmppMessage = (org.jivesoftware.smack.packet.Message) packet;

            Date timestamp = null;
            for (PacketExtension extension : xmppMessage.getExtensions()) {
                if ("jabber:x:delay".equals(extension.getNamespace())) {
                    DelayInformation delayInformation = (DelayInformation) extension;
                    timestamp = delayInformation.getStamp();
                } else {
                    LOG.debug("Found unknown packet extenstion {} with namespace {}",
                            extension.getClass().getSimpleName(),
                            extension.getNamespace()
                    );
                }
            }

            boolean history = true;
            if (timestamp == null) {
                timestamp = new Date();
                history = false;
            }

            if (history) {
                mucListener.historyMessageReceived(
                        xmppMessage.getFrom(),
                        xmppMessage.getBody(),
                        timestamp
                );
            } else {
                mucListener.messageReceived(
                        xmppMessage.getFrom(),
                        xmppMessage.getBody(),
                        timestamp
                );
            }

            lastActivity = timestamp;
        }
    }
}
