package peersim.gossipsub;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import peersim.blockpropagation.GossipObserver;
import peersim.blockpropagation.Sample;
import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Network;
import peersim.core.Node;
import peersim.edsim.EDProtocol;
import peersim.kademlia.SimpleEvent;
import peersim.transport.UnreliableTransport;

public class GossipSubProtocol implements Cloneable, EDProtocol {

  /** Prefix for configuration parameters. */
  protected static String prefix = null;

  /** UnreliableTransport object used for communication. */
  private UnreliableTransport transport;

  /** The parameter name for transport. */
  private static final String PAR_TRANSPORT = "transport";

  private static final String PAR_HEARTBEAT = "heartbeat";

  private static final String PAR_DEGREE = "d";
  private static final String PAR_DEGREE_HIGH = "d_high";
  private static final String PAR_DEGREE_LOW = "d_low";

  /** Identifier for the tranport protocol (used in the sendMessage method) */
  private int tid;

  /** Unique ID for this Kademlia node/network */
  protected int gossipid;

  /** Indicates if the service initializer has already been called. */
  private static boolean _ALREADY_INSTALLED = false;

  /** Kademlia node instance. */
  public GossipNode node;

  /** Logging handler. */
  protected Logger logger;

  private PeerTable peers;

  protected HashMap<String, HashSet<BigInteger>> mesh;

  private HashMap<String, HashSet<BigInteger>> fanout;

  private HashMap<String, Long> fanoutExpirations;

  protected MCache mCache;

  protected HashMap<String, List<Long>> seen;

  private long heartbeat;

  private int D_low, D_high, D;
  /**
   * Replicate this object by returning an identical copy. It is called by the initializer and do
   * not fill any particular field.
   *
   * @return Object
   */
  public Object clone() {
    GossipSubProtocol dolly = new GossipSubProtocol(GossipSubProtocol.prefix);
    return dolly;
  }

  /**
   * Constructor for KademliaProtocol. It is only used by the initializer when creating the
   * prototype. Every other instance calls CLONE to create a new object.
   *
   * @param prefix String: the prefix for configuration parameters
   */
  public GossipSubProtocol(String prefix) {
    this.node = null; // empty nodeId
    GossipSubProtocol.prefix = prefix;

    _init();

    tid = Configuration.getPid(prefix + "." + PAR_TRANSPORT);
    heartbeat = Configuration.getLong(prefix + "." + PAR_HEARTBEAT);
    D = Configuration.getInt(prefix + "." + PAR_DEGREE, GossipCommonConfig.D);
    D_high = Configuration.getInt(prefix + "." + PAR_DEGREE_HIGH, GossipCommonConfig.D_high);
    D_low = Configuration.getInt(prefix + "." + PAR_DEGREE_LOW, GossipCommonConfig.D_low);

    if (D_low < 1) D_low = 1;
    seen = new HashMap<>();
    peers = new PeerTable();

    mesh = new HashMap<>();

    fanout = new HashMap<>();

    mCache = new MCache();
    // System.out.println("New GossipSubProtocol " + D + " " + D_high + " " + D_low);
  }

  /**
   * This procedure is called only once and allows to initialize the internal state of
   * KademliaProtocol. Every node shares the same configuration, so it is sufficient to call this
   * routine once.
   */
  private void _init() {
    // execute once
    if (_ALREADY_INSTALLED) return;

    _ALREADY_INSTALLED = true;
  }

  /**
   * Search through the network for a node with a specific node ID, using binary search based on the
   * ordering of the network. If the binary search does not find a node with the given ID, a
   * traditional search is performed for more reliability (in case the network is not ordered).
   *
   * @param searchNodeId the ID of the node to search for
   * @return the node with the given ID, or null if not found
   */
  protected Node nodeIdtoNode(BigInteger searchNodeId) {
    // If the given searchNodeId is null, return null
    if (searchNodeId == null) return null;

    // Set the initial search range to cover the entire network
    int inf = 0;
    int sup = Network.size() - 1;
    int m;

    // Perform binary search until the search range is empty
    while (inf <= sup) {
      // Calculate the midpoint of the search range
      m = (inf + sup) / 2;

      // Get the ID of the node at the midpoint
      BigInteger mId =
          ((GossipSubProtocol) Network.get(m).getProtocol(gossipid)).getGossipNode().getId();

      // If the midpoint node has the desired ID, return it
      if (mId.equals(searchNodeId)) return Network.get(m);

      // If the midpoint node has a smaller ID than the desired ID, narrow the search range to the
      // upper half of the current range
      if (mId.compareTo(searchNodeId) < 0) inf = m + 1;
      // Otherwise, narrow the search range to the lower half of the current range
      else sup = m - 1;
    }

    // If the binary search did not find a node with the desired ID, perform a traditional search
    // through the network
    BigInteger mId;
    for (int i = Network.size() - 1; i >= 0; i--) {
      mId = ((GossipSubProtocol) Network.get(i).getProtocol(gossipid)).getGossipNode().getId();
      if (mId.equals(searchNodeId)) return Network.get(i);
    }

    // If no node with the desired ID was found, return null
    return null;
  }

  /**
   * Gets the node associated with this Kademlia protocol instance by calling nodeIdtoNode method
   * with the ID of this KademliaNod.
   *
   * @return the node associated with this Kademlia protocol instance,
   */
  public Node getNode() {
    return nodeIdtoNode(this.getGossipNode().getId());
  }

  protected void sendGraftMessage(BigInteger id, String topic) {
    Message m = Message.makeGraftMessage(topic);
    m.src = this.node;
    m.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(gossipid)).getGossipNode();
    sendMessage(m, id, gossipid);
  }

  protected void sendIHaveMessage(String topic, BigInteger id, List<Long> ids) {
    Message m = Message.makeIHaveMessage(topic, ids);
    m.src = this.node;
    m.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(gossipid)).getGossipNode();
    sendMessage(m, id, gossipid);
  }

  protected void sendPruneMessage(BigInteger id, String topic) {
    Message m = Message.makePruneMessage(topic);
    m.src = this.node;
    m.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(gossipid)).getGossipNode();
    sendMessage(m, id, gossipid);
  }

  protected void sendIWantMessage(String topic, BigInteger id, List<Long> ids) {
    Message m = Message.makeIWantMessage(topic, ids);
    m.src = this.node;
    m.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(gossipid)).getGossipNode();
    sendMessage(m, id, gossipid);
  }
  /**
   * Sends a message using the current transport layer and starts the timeout timer if the message
   * is a request.
   *
   * @param m the message to send
   * @param destId the ID of the destination node
   * @param myPid the sender process ID (Todo: verify what myPid stand for!!!)
   */
  protected void sendMessage(Message m, BigInteger destId, int myPid) {

    // Assert that message source and destination nodes are not null
    assert m.src != null;
    assert m.dst != null;

    // Get source and destination nodes
    Node src = nodeIdtoNode(this.getGossipNode().getId());
    Node dest = nodeIdtoNode(destId);

    // destpid = dest.getKademliaProtocol().getProtocolID();

    if (m.getType() == Message.MSG_PUBLISH || m.getType() == Message.MSG_MESSAGE)
      logger.warning(
          "Sending message "
              + m.getType()
              + " to "
              + destId
              + " "
              + ((GossipSubProtocol) dest.getProtocol(myPid)).getGossipNode().getId()
              + " from "
              + this.getGossipNode().getId()
              + " "
              + ((GossipSubProtocol) src.getProtocol(myPid)).getGossipNode().getId()
              + " "
              + m.getType()
              + " "
              + m.getHops());
    // Get the transport protocol
    m.increaseHops();
    transport = (UnreliableTransport) (Network.prototype).getProtocol(tid);

    // Send the message
    transport.send(src, dest, m, gossipid);
  }

  /**
   * Get the current KademliaNode object.
   *
   * @return The current KademliaNode object.
   */
  public GossipNode getGossipNode() {
    return this.node;
  }

  /**
   * Set the protocol ID for this node.
   *
   * @param protocolID The protocol ID to set.
   */
  public void setProtocolID(int protocolID) {
    this.gossipid = protocolID;
  }

  /**
   * Get the protocol ID for this node.
   *
   * @return The protocol ID for this node.
   */
  public int getProtocolID() {
    return this.gossipid;
  }

  /**
   * Sets the current Kademlia node and its routing table.
   *
   * @param node The KademliaNode object to set.
   */
  public void setNode(GossipNode node) {
    this.node = node;

    // Initialize the logger with the node ID as its name
    logger = Logger.getLogger(node.getId().toString());

    // Disable the logger's parent handlers to avoid duplicate output
    logger.setUseParentHandlers(false);

    // Set the logger's level to WARNING
    logger.setLevel(Level.WARNING);
    // logger.setLevel(Level.ALL);

    // Create a console handler for the logger
    ConsoleHandler handler = new ConsoleHandler();
    // Set the handler's formatter to a custom format that includes the time and logger name
    handler.setFormatter(
        new SimpleFormatter() {
          private static final String format = "[%d][%s] %3$s %n";

          @Override
          public synchronized String format(LogRecord lr) {
            return String.format(format, CommonState.getTime(), logger.getName(), lr.getMessage());
          }
        });
    // Add the console handler to the logger
    logger.addHandler(handler);
  }

  /**
   * Get the logger associated with this Kademlia node.
   *
   * @return The logger object.
   */
  public Logger getLogger() {
    return this.logger;
  }

  @Override
  public void processEvent(Node node, int pid, Object event) {
    // Set the Kademlia ID as the current process ID - assuming Pid stands for process ID.
    this.gossipid = pid;

    Message m;

    // If the event is a message, report the message to the Kademlia observer.
    if (event instanceof Message) {
      m = (Message) event;
      // KademliaObserver.reportMsg(m, false);
      /*if (m.src != null)
        logger.warning("Message received " + m.getType() + " from " + m.src.getId());
      else logger.warning("Message src null " + m.getType());*/

      GossipObserver.reportMsg(m, false);
    }

    // Handle the event based on its type.
    switch (((SimpleEvent) event).getType()) {
      case Message.MSG_JOIN:
        m = (Message) event;
        // sentMsg.remove(m.ackId);
        handleJoin(m, pid);
        break;
      case Message.MSG_LEAVE:
        m = (Message) event;
        // sentMsg.remove(m.ackId);
        handleLeave(m, pid);
        break;
      case Message.MSG_PUBLISH:
        m = (Message) event;
        handlePublish(m, pid);
        break;
      case Message.MSG_MESSAGE:
        m = (Message) event;
        handleMessage(m, pid);
        break;
      case Message.MSG_GRAFT:
        m = (Message) event;
        handleGraft(m, pid);
        break;
      case Message.MSG_IHAVE:
        m = (Message) event;
        handleIHave(m, pid);
        break;
      case Message.MSG_IWANT:
        m = (Message) event;
        handleIWant(m, pid);
        break;
      case Message.MSG_PRUNE:
        m = (Message) event;
        handlePrune(m, pid);
        break;
    }
  }

  public void heartBeat() {
    for (String topic : mesh.keySet()) {
      logger.warning("heartbeat execute " + mesh.get(topic).size() + " " + topic);
      if (mesh.get(topic).size() < D_low) {
        List<BigInteger> nodes =
            peers.getNPeers(topic, D - mesh.get(topic).size(), mesh.get(topic));
        nodes.remove(this.node.getId());
        mesh.get(topic).addAll(nodes);
        for (BigInteger id : nodes) {
          sendGraftMessage(id, topic);
        }
      }
      if (mesh.get(topic).size() > D_high) {
        HashSet<BigInteger> nodes = mesh.get(topic);
        int toRemove = mesh.get(topic).size() - D_high;
        for (int i = 0; i < toRemove; i++) {
          BigInteger node = nodes.iterator().next();
          nodes.remove(node);
          logger.info("Pruning node " + node);
          sendPruneMessage(node, topic);
        }
      }
    }
    for (String topic : fanout.keySet()) {
      if (fanoutExpirations.get(topic) > CommonState.getTime()) {
        fanout.remove(topic);
        fanoutExpirations.remove(topic);
      } else {
        if (fanout.get(topic).size() < D) {
          List<BigInteger> nodes =
              peers.getNPeers(topic, D - mesh.get(topic).size(), mesh.get(topic));
          fanout.get(topic).addAll(nodes);
        }
      }
    }
    HashSet<String> allTopics = new HashSet<>();
    allTopics.addAll(mesh.keySet());
    allTopics.addAll(fanout.keySet());

    for (String topic : allTopics) {

      logger.info("Sending gossip topic " + topic);
      List<Long> msgs = seen.get(topic);
      if (msgs != null) {

        List<BigInteger> ids = peers.getPeers(topic);

        if (ids == null) break;
        int sent = 0;
        boolean found = false;

        logger.info(
            "Sending gossip msgs " + msgs.size() + " " + ids.size() + " " + mesh.get(topic).size());

        for (BigInteger id : ids) {
          if (mesh.get(topic) != null) {
            if (mesh.get(topic).contains(id)) {
              found = true;
            }
          }
          if (fanout.get(topic) != null) {
            if (fanout.get(topic).contains(id)) {
              found = true;
            }
          }
          logger.warning("Sending gossip to " + id + " " + !found);

          if (!found) {
            logger.warning("Sending gossip to " + id);
            sendIHaveMessage(topic, id, msgs);
            sent++;
          }
          if (sent == D) break;
          found = false;
        }
      }
    }
  }

  private void handleJoin(Message m, int myPid) {
    String topic = (String) m.body;
    logger.warning("Handlejoin received " + topic);

    /*EDSimulator.add(
    0,
    Message.makePublishMessage(
        "Discovery", topic + ":" + this.getGossipNode().getId().toString()),
    getNode(),
    myPid);*/

    if (mesh.get(topic) != null) return;
    if (fanout.get(topic) != null) {
      HashSet<BigInteger> p = fanout.get(topic);
      mesh.put(topic, p);
      fanout.remove(topic);
      if (p.size() < D) {
        List<BigInteger> p2 = peers.getNPeers(topic, D - p.size(), mesh.get(topic));
        p2.remove(this.node.getId());
        for (BigInteger id : p2) {
          mesh.get(topic).add(id);
        }
      }
    } else if (mesh.get(topic) == null) {
      List<BigInteger> p = peers.getPeers(topic);
      mesh.put(topic, new HashSet<BigInteger>());

      if (p != null) {
        // Collections.shuffle(p);
        p.remove(this.node.getId());
        for (BigInteger id : p) {
          if (mesh.get(topic).size() >= D) break;
          logger.warning("Adding " + id + " to mesh");
          mesh.get(topic).add(id);
        }
      }
    }
    if (mesh.get(topic) != null) {
      HashSet<BigInteger> p = mesh.get(topic);
      for (BigInteger id : p) {
        sendGraftMessage(id, topic);
      }
    }
  }

  private void handleLeave(Message m, int myPid) {
    String topic = (String) m.body;
    logger.warning("Handleleave received " + topic);
    if (mesh.get(topic) != null) {
      HashSet<BigInteger> p = mesh.get(topic);
      for (BigInteger id : p) {
        sendPruneMessage(id, topic);
      }
      mesh.remove(topic);
    }
  }

  private void handleGraft(Message m, int myPid) {
    String topic = (String) m.body;
    logger.info("handleGraft received " + topic + " from:" + m.src.getId());
    // if (mesh.get(topic) == null) mesh.put(topic, new ArrayList<BigInteger>());
    if (mesh.get(topic) != null) mesh.get(topic).add(m.src.getId());
    peers.addPeer(topic, m.src.getId());
  }

  private void handlePrune(Message m, int myPid) {

    String topic = (String) m.body;

    logger.warning("handlePrune received " + topic + " " + m.src.getId());

    if (mesh.get(topic) != null) {
      mesh.get(topic).remove(m.src.getId());
    } else {
      logger.warning("handlePrune not found");
    }
  }

  private void handleIHave(Message m, int myPid) {
    String topic = (String) m.body;

    List<Long> msgIds = (List<Long>) m.value;
    List<Long> iwants = new ArrayList<>();
    List<Long> have = seen.get(topic);

    logger.info("handleIHave received " + topic + " " + msgIds.size());

    if (have != null) {
      for (long msg : msgIds) {
        if (!have.contains(msg)) iwants.add(msg);
      }
    } else {
      iwants.addAll(msgIds);
    }
    if (iwants.size() > 0) sendIWantMessage(topic, m.src.getId(), iwants);
  }

  private void handleIWant(Message m, int myPid) {
    logger.warning("handleIWant received " + m.body);
    List<Long> ids = (List<Long>) m.value;
    for (long id : ids) {
      if (mCache.get(id) != null) {
        Message msg = Message.makeMessage((String) m.body, mCache.get(id));
        msg.src = this.node;
        msg.dst = m.src;
        msg.setSize(((Sample) msg.value).getSize());
        msg.publisher = ((Sample) msg.value).getBlock().getPublisher();
        long cid = ((Sample) msg.value).getId();

        logger.warning("sending message iwant " + cid + " " + msg.id + " to " + msg.dst.getId());

        sendMessage(msg, m.src.getId(), myPid);
      }
    }
    //
  }

  private void handlePublish(Message m, int myPid) {

    String topic = (String) m.body;
    Sample s = (Sample) m.value;
    logger.warning(
        "Publish message "
            + topic
            + " "
            + mesh.get(topic).size()
            + " "
            + s.getBlock().getId()
            + " "
            + gossipid);

    if (seen.get(topic) == null) seen.put(topic, new ArrayList<Long>());

    // BigInteger cid = getValueId(m.value);
    long cid = s.getId();
    seen.get(topic).add(cid);
    mCache.put(cid, s);

    if (mesh.get(topic) != null) {
      HashSet<BigInteger> nodesToSend = mesh.get(topic);
      nodesToSend.remove(this.node.getId());
      for (BigInteger id : nodesToSend) {

        Message msg = Message.makeMessage(topic, s);
        msg.setSize(s.getSize());
        msg.src = this.node;
        msg.publisher = this.node;
        msg.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(gossipid)).getGossipNode();
        sendMessage(msg, id, gossipid);
      }
    }
  }

  protected void handleMessage(Message m, int myPid) {

    logger.warning("handleMessage received from " + m.src.getId());
    if (m.publisher.getId().compareTo(this.getGossipNode().getId()) == 0) return;
    if (m.src.getId().compareTo(this.getGossipNode().getId()) == 0) return;

    String topic = (String) m.body;

    Sample s = (Sample) m.value;
    long cid = s.getId();

    if (seen.get(topic) == null) seen.put(topic, new ArrayList<Long>());
    if (seen.get(topic).contains(cid)) return;
    seen.get(topic).add(cid);

    mCache.put(cid, s);
    logger.warning(
        "handleMessage received "
            + topic
            + " "
            + cid
            + " "
            + m.id
            + " "
            + m.src.getId()
            + " "
            + m.nrHops
            + " "
            + m.publisher.getId());

    if (mesh.get(topic) != null) {
      HashSet<BigInteger> nodesToSend = mesh.get(topic);
      nodesToSend.remove(m.src.getId());
      nodesToSend.remove(this.node.getId());
      nodesToSend.remove(m.publisher.getId());
      for (BigInteger id : nodesToSend) {
        Message mbis = m.copy();
        mbis.dst = ((GossipSubProtocol) nodeIdtoNode(id).getProtocol(myPid)).getGossipNode();
        mbis.src = this.getGossipNode();
        logger.warning(
            "handleMessage resending " + cid + " " + m.id + " to " + id + " " + m.dst.getId());
        sendMessage(mbis, id, myPid);
      }
    }
  }

  /*private BigInteger getValueId(Object obj) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ObjectOutputStream oos;
    byte[] hash;
    try {
      oos = new ObjectOutputStream(bos);
      oos.writeObject(obj);
      oos.flush();
      byte[] data = bos.toByteArray();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      hash = digest.digest(data);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      return null;
    }

    return new BigInteger(1, hash);
  }*/

  public PeerTable getTable() {
    return this.peers;
  }
}
