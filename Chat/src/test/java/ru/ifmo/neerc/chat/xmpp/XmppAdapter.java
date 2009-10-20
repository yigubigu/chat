package ru.ifmo.neerc.chat.xmpp;

import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.ParticipantStatusListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.neerc.chat.MessageListener;
import ru.ifmo.neerc.chat.UserEntry;
import ru.ifmo.neerc.chat.UserRegistry;
import ru.ifmo.neerc.chat.message.Message;
import ru.ifmo.neerc.chat.message.ServerMessage;
import ru.ifmo.neerc.chat.message.UserMessage;
import ru.ifmo.neerc.chat.message.UserText;

import java.util.Iterator;

/**
 * Adapts XMPP protocol to neerc-chat protocol, which based on {@link ru.ifmo.neerc.chat.message.Message}.
 *
 * @author Evgeny Mandrikov
 */
public abstract class XmppAdapter implements PacketListener, MessageListener, ParticipantStatusListener {
    private static final Logger LOG = LoggerFactory.getLogger(XmppAdapter.class);

    private MultiUserChat chat;

    private UserEntry getUser(String participant) {
        final UserRegistry userRegistry = UserRegistry.getInstance();

        final String name = participant.substring(participant.indexOf('/') + 1);
        UserEntry user = userRegistry.findByName(name);

        if (user == null) {
//            final String role = chat.getOccupant(participant).getRole();

            final int id = userRegistry.getUserNumber() + 1;
            LOG.debug("Added user {} with id {}", participant, id);
            user = new UserEntry(
                    id,
                    name,
                    false // TODO power
            );
            userRegistry.register(user);
        }
        return user;
    }

    @Override
    public void processPacket(Packet packet) {
        // TODO unchecked cast
        org.jivesoftware.smack.packet.Message xmppMessage = (org.jivesoftware.smack.packet.Message) packet;
        UserEntry user = getUser(xmppMessage.getFrom());
        UserMessage message = new UserMessage(
                user.getId(),
                new UserText(xmppMessage.getBody())
        );
        processMessage(message);
    }

    @Override
    public abstract void processMessage(Message message);

    @Override
    public void joined(String participant) {
        LOG.debug("JOINED: {}", participant);
        processMessage(new ServerMessage(ServerMessage.USER_JOINED, getUser(participant)));
    }

    @Override
    public void left(String participant) {
        LOG.debug("LEFT: {}", participant);
        processMessage(new ServerMessage(ServerMessage.USER_LEFT, getUser(participant)));
    }

    @Override
    public void kicked(String s, String s1, String s2) {
        LOG.debug("KICKED: {} {} {}", new Object[]{s, s1, s2});
    }

    @Override
    public void voiceGranted(String participant) {
        LOG.debug("+VOICE: {}", participant);
    }

    @Override
    public void voiceRevoked(String participant) {
        LOG.debug("-VOICE: {}", participant);
    }

    @Override
    public void banned(String s, String s1, String s2) {
        LOG.debug("BANNED: {} {} {}", new Object[]{s, s1, s2});
    }

    @Override
    public void membershipGranted(String participant) {
        LOG.debug("+MEMBER: {}", participant);
    }

    @Override
    public void membershipRevoked(String participant) {
        LOG.debug("-MEMBER: {}", participant);
    }

    @Override
    public void moderatorGranted(String participant) {
        LOG.debug("+MODERATOR: {}", participant);
    }

    @Override
    public void moderatorRevoked(String participant) {
        LOG.debug("-MODERATOR: {}", participant);
    }

    @Override
    public void ownershipGranted(String participant) {
        LOG.debug("+OWNER: {}", participant);
    }

    @Override
    public void ownershipRevoked(String participant) {
        LOG.debug("-OWNER: {}", participant);
    }

    @Override
    public void adminGranted(String participant) {
        LOG.debug("+ADMIN: {}", participant);
    }

    @Override
    public void adminRevoked(String participant) {
        LOG.debug("-ADMIN: {}", participant);
    }

    @Override
    public void nicknameChanged(String s, String s1) {
        LOG.debug("NICKNAME: {} - {}", s, s1);
    }

    public void registerListeners(MultiUserChat chat) {
        this.chat = chat;
        chat.addMessageListener(this);
        chat.addParticipantStatusListener(this);

        LOG.debug("Occupants count = " + chat.getOccupantsCount());
        Iterator<String> occupants = chat.getOccupants();
        while (occupants.hasNext()) {
            String occupant = occupants.next();
            String nick = chat.getOccupant(occupant).getNick();
            LOG.debug(nick);
            UserRegistry.getInstance().putOnline(getUser(nick), true);
        }
    }
}
