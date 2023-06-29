package peersim.gossipsub;

import java.math.BigInteger;
import peersim.config.Configuration;
import peersim.core.CommonState;
import peersim.core.Control;
import peersim.core.Network;
import peersim.core.Node;
import peersim.edsim.EDSimulator;
import peersim.kademlia.das.Block;
import peersim.kademlia.das.Sample;

/**
 * This control generates samples every 5 min that are stored in a single node (builder) and starts
 * random sampling from the rest of the nodes In parallel, random lookups are started to start
 * discovering nodes
 *
 * @author Sergi Rene
 * @version 1.0
 */

// ______________________________________________________________________________________________
public class TrafficGeneratorRowColumn implements Control {

  // ______________________________________________________________________________________________
  /** MSPastry Protocol to act */
  private static final String PAR_PROT = "protocol";

  /** Mapping function for samples */
  final String PAR_MAP_FN = "mapping_fn";

  /** Number of sample copies stored per node */
  final String PAR_NUM_COPIES = "sample_copy_per_node";

  final String PAR_BLK_DIM_SIZE = "block_dim_size";

  int mapfn;

  Block b;

  private int protocol;

  private boolean first = true, second = false;

  private long ID_GENERATOR = 0;

  // ______________________________________________________________________________________________
  public TrafficGeneratorRowColumn(String prefix) {

    GossipCommonConfig.BLOCK_DIM_SIZE =
        Configuration.getInt(prefix + "." + PAR_BLK_DIM_SIZE, GossipCommonConfig.BLOCK_DIM_SIZE);

    protocol = Configuration.getPid(prefix + "." + PAR_PROT);
  }

  private Message generateNewBlockMessage(Block b) {

    Message m = Message.makeInitNewBlock(b);
    m.timestamp = CommonState.getTime();

    return m;
  }
  // ______________________________________________________________________________________________
  /**
   * every call of this control generates and send a random find node message
   *
   * @return boolean
   */
  public boolean execute() {
    if (first) {
      first = false;
      second = true;
      for (int i = 0; i < Network.size(); i++) {
        Node n = Network.get(i);
        GossipSubProtocol prot = (GossipSubProtocol) n.getProtocol(protocol);
        BigInteger id = prot.getGossipNode().getId();
        if (i == 0) {
          System.out.println("Builder " + id);
          for (int j = 1; j <= GossipCommonConfig.BLOCK_DIM_SIZE; j++) {
            EDSimulator.add(0, Message.makeInitJoinMessage("Row" + j), n, protocol);
            EDSimulator.add(0, Message.makeInitJoinMessage("Column" + j), n, protocol);
          }

          for (int l = 0; l < Network.size(); l++) {
            Node n2 = Network.get(l);
            GossipSubProtocol prot2 = (GossipSubProtocol) n2.getProtocol(protocol);
            for (int m = 1; m <= GossipCommonConfig.BLOCK_DIM_SIZE; m++) {
              prot2.getTable().addPeer("Row" + m, id);
              prot2.getTable().addPeer("Column" + m, id);
            }
          }
        } /*else {
            int r1 = CommonState.r.nextInt(GossipCommonConfig.BLOCK_DIM_SIZE) + 1;
            EDSimulator.add(0, Message.makeInitJoinMessage("Row" + r1), n, protocol);
            int r2 = CommonState.r.nextInt(GossipCommonConfig.BLOCK_DIM_SIZE) + 1;
            EDSimulator.add(0, Message.makeInitJoinMessage("Row" + r2), n, protocol);
            int c1 = CommonState.r.nextInt(GossipCommonConfig.BLOCK_DIM_SIZE) + 1;
            EDSimulator.add(0, Message.makeInitJoinMessage("Column" + c1), n, protocol);
            int c2 = CommonState.r.nextInt(GossipCommonConfig.BLOCK_DIM_SIZE) + 1;
            EDSimulator.add(0, Message.makeInitJoinMessage("Column" + c2), n, protocol);
            for (int l = 0; l < Network.size(); l++) {
              Node n2 = Network.get(l);
              GossipSubProtocol prot2 = (GossipSubProtocol) n2.getProtocol(protocol);
              prot2.getTable().addPeer("Row" + r1, id);
              prot2.getTable().addPeer("Row" + r2, id);
              prot2.getTable().addPeer("Column" + c1, id);
              prot2.getTable().addPeer("Column" + c2, id);
            }
          }*/
      }

    } else {
      Block b = new Block(GossipCommonConfig.BLOCK_DIM_SIZE, ID_GENERATOR);

      Node n = Network.get(0);

      for (int i = 0; i < GossipCommonConfig.BLOCK_DIM_SIZE; i++) {
        for (int j = 0; j < GossipCommonConfig.BLOCK_DIM_SIZE; j++) {
          Sample s = b.getSample(i, j);
          String topic = "Row" + (s.getRow());
          System.out.println("Topic " + topic);
          EDSimulator.add(0, Message.makePublishMessage(topic, s), n, protocol);
          topic = "Column" + (s.getColumn());
          EDSimulator.add(0, Message.makePublishMessage(topic, s), n, protocol);
        }
      }
      for (int i = 1; i < Network.size(); i++) {
        Node n2 = Network.get(i);
        EDSimulator.add(0, generateNewBlockMessage(b), n2, protocol);
      }
      second = false;
      ID_GENERATOR++;
    }
    return false;
  }

  // ______________________________________________________________________________________________

} // End of class
// ______________________________________________________________________________________________
