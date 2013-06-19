/*
 * Copyright 3012 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import akka.dispatch.{Promise, Future, ExecutionContext}
import akka.actor.{ActorContext, ActorSystem}
import java.util.UUID

import org.midonet.midolman.rules.RuleResult.{Action => RuleAction}
import org.midonet.midolman.topology.VirtualTopologyActor.expiringAsk
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.midolman.topology.{FlowTagger, RoutingTableWrapper, TagManager, RouterConfig}
import org.midonet.packets.{MAC, Unsigned, Ethernet, IPAddr}
import org.midonet.midolman.logging.{LoggerFactory, SimulationAwareBusLogging}
import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.layer3.Route
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.simulation.Coordinator.DropAction
import org.midonet.midolman.topology.RouterConfig
import org.midonet.midolman.simulation.Coordinator.ErrorDropAction

/**
 * Defines the base Router device that is meant to be extended with specific
 * implementations for IPv4 and IPv6 that deal with version specific details
 * such as ARP vs. NDP.
 */
abstract class RouterBase[IP <: IPAddr]()(implicit context: ActorContext)
    extends Device {
    val id: UUID
    val cfg: RouterConfig
    val rTable: RoutingTableWrapper[IP]
    val inFilter: Chain
    val outFilter: Chain
    val routerMgrTagger: TagManager

    val validEthertypes: Set[Short]
    val log = LoggerFactory.getSimulationAwareLog(this.getClass)(context.system.eventStream)

    val loadBalancer = new LoadBalancer(rTable)

    protected def unsupportedPacketAction: Action

    /**
     * Process the packet and
     *
     * @param pktContext The context for the simulation of this packet's
     *                   traversal of the virtual network. Use the context to
     *                   subscribe for notifications on the removal of any
     *                   resulting flows, or to tag any resulting flows for
     *                   indexing.
     * @param ec         (implicit)
     * @param actorSystem (implicit)
     * @return An instance of Action that reflects what the device would do
     *         after handling this packet (e.g. drop it, consume it, forward it)
     */
    override def process(pktContext: PacketContext)
                        (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem): Future[Action] = {
        implicit val packetContext = pktContext

        if (!validEthertypes.contains(pktContext.getMatch.getEtherType)) {
            return Promise.successful(unsupportedPacketAction)(ec)
        }

        getRouterPort(pktContext.getInPortId, pktContext.getExpiry) flatMap {
            case null => log.debug("Router - in port {} was null",
                pktContext.getInPortId())
            Promise.successful(new DropAction)(ec)
            case inPort => preRouting(inPort)
        }
    }

    private def applyIngressChain(inPort: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  pktContext: PacketContext,
                                  actorSystem: ActorSystem): Option[Action] = {

        // Apply the pre-routing (ingress) chain
        pktContext.setOutputPort(null) // input port should be set already
        val preRoutingResult = Chain.apply(inFilter, pktContext,
                pktContext.getMatch, id, false)

        if (preRoutingResult.action == RuleAction.DROP) {
            Some(new DropAction)
        } else if (preRoutingResult.action == RuleAction.REJECT) {
            sendIcmpUnreachableProhibError(inPort, pktContext.getMatch,
                pktContext.getFrame)
            Some(new DropAction)
        } else if (preRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Pre-routing for {} returned an action which was {}, " +
                "not ACCEPT, DROP, or REJECT.", id, preRoutingResult.action)
            Some(new ErrorDropAction)
        }
        if (preRoutingResult.pmatch ne pktContext.getMatch) {
            log.error("Pre-routing for {} returned a different match obj.", id)
            Some(new ErrorDropAction)
        }
        None
    }


    private def preRouting(inPort: RouterPort[_])
                          (implicit pktContext: PacketContext,
                                    ec: ExecutionContext,
                                    actorSystem: ActorSystem): Future[Action] ={

        val hwDst = pktContext.getMatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            log.debug("Received an L2 broadcast packet.")
            return handleL2Broadcast(inPort)
        }

        if (hwDst != inPort.portMac) { // Not addressed to us, log.warn and drop
            log.warning("{} neither broadcast nor inPort's MAC ({})", hwDst,
                inPort.portMac)
            return Promise.successful(new DropAction)(ec)
        }

        pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))
        handleNeighbouring(inPort) match {
            case Some(a: Action) => return Promise.successful(a)(ec)
            case None =>
        }

        applyIngressChain(inPort) match {
            case Some(a: Action) => return Promise.successful(a)(ec)
            case None =>
        }

        routing(inPort)

    }

    private def routing(inPort: RouterPort[_])
                       (implicit ec: ExecutionContext,
                                 pktContext: PacketContext,
                                 actorSystem: ActorSystem): Future[Action] = {

        val pMatch = pktContext.getMatch
        val pFrame = pktContext.getFrame

        /* TODO(D-release): Have WildcardMatch take a DecTTLBy instead,
         * so that there need only be one sim. run for different TTLs.  */
        if (pMatch.getNetworkTTL != null) {
            val ttl = Unsigned.unsign(pMatch.getNetworkTTL)
            if (ttl <= 1) {
                sendIcmpTimeExceededError(inPort, pMatch, pFrame)
                Promise.successful(new DropAction)(ec)
            } else {
                pMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }

        // tag using the destination IP
        val dstIP = pMatch.getNetworkDestinationIP
        pktContext.addFlowTag(FlowTagger.invalidateByIp(id, dstIP))
        // pass tag to the RouterManager so it'll be able to invalidate the flow
        routerMgrTagger.addTag(dstIP)
        // register the tag removal callback
        pktContext.addFlowRemovedCallback(routerMgrTagger
                                          .getFlowRemovalCallback(dstIP))

        val rt: Route = loadBalancer.lookup(pMatch)
        if (rt == null) {
            // No route to network
            log.debug("Route lookup: No route to network (dst:{}), {}",
                dstIP, rTable.rTable)
            sendIcmpUnreachableNetError(inPort, pMatch, pFrame)
            return Promise.successful(new DropAction)(ec)
        }

        // tag using this route
        pktContext.addFlowTag(FlowTagger.invalidateByRoute(id, rt.hashCode()))

        rt.nextHop match {
            case Route.NextHop.LOCAL =>
                if (isIcmpEchoRequest(pMatch)) {
                    log.debug("got ICMP echo")
                    sendIcmpEchoReply(pMatch, pFrame, pktContext.getExpiry)
                    Promise.successful(new ConsumedAction)(ec)
                } else {
                    Promise.successful(new DropAction)(ec)
                }

            case Route.NextHop.BLACKHOLE =>
                log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                    pMatch.getNetworkDestinationIP)
                Promise.successful(new DropAction)(ec)

            case Route.NextHop.REJECT =>
                sendIcmpUnreachableProhibError(inPort, pMatch, pFrame)
                log.debug("Dropping packet, REJECT route (dst:{})",
                    pMatch.getNetworkDestinationIP)
                Promise.successful(new DropAction)(ec)

            case Route.NextHop.PORT =>
                if (rt.nextHopPort == null) {
                    log.error("Routing table lookup for {} forwarded to port " +
                        "null.", dstIP)
                    // TODO(pino): should we remove this route?
                    Promise.successful(new DropAction)(ec)
                } else {
                    getRouterPort(rt.nextHopPort, pktContext.getExpiry) flatMap {
                        case null =>
                            Promise.successful(new ErrorDropAction)(ec)
                        case outPort =>
                            postRouting(inPort, outPort, rt, pktContext)
                    }
                }

            case _ =>
                log.error("Routing table lookup for {} returned invalid " +
                    "nextHop of {}", dstIP, rt.nextHop)
                // rt.nextHop is invalid. The only way the simulation result
                // would change is if there are other matching routes that are
                // 'sane'. If such routes were created, this flow will be
                // invalidated. Thus, we can return DropAction and not
                // ErrorDropAction
                Promise.successful(new DropAction)(ec)
        }

    }

    // POST ROUTING
    private def postRouting(inPort: RouterPort[_], outPort: RouterPort[_],
                            rt: Route, pktContext: PacketContext)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext = pktContext

        val pMatch = pktContext.getMatch
        val pFrame = pktContext.getFrame

        pktContext.setOutputPort(outPort.id)
        val postRoutingResult = Chain.apply(outFilter, pktContext, pMatch,
                                            id, false)

        if (postRoutingResult.action == RuleAction.DROP) {
            log.debug("PostRouting DROP rule")
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action == RuleAction.REJECT) {
            log.debug("PostRouting REJECT rule")
            sendIcmpUnreachableProhibError(inPort, pMatch, pFrame)
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Post-routing for {} returned an action which was {}, " +
                "not ACCEPT, DROP, or REJECT.", id, postRoutingResult.action)
            return Promise.successful(new ErrorDropAction)
        }

        if (postRoutingResult.pmatch ne pMatch) {
            log.error("Post-routing for {} returned a different match obj.", id)
            return Promise.successful(new ErrorDropAction)(ec)
        }

        if (pMatch.getNetworkDestinationIP == outPort.portAddr.getAddress) {
            log.error("Got a packet addressed to a port without a LOCAL route")
            return Promise.successful(new DropAction)(ec)
        }

        // Set HWSrc
        pMatch.setEthernetSource(outPort.portMac)

        // Set HWDst
        val macFuture = getNextHopMac(outPort, rt,
                              pMatch.getNetworkDestinationIP.asInstanceOf[IP],
                              pktContext.getExpiry)

        macFuture map {
            case null =>
                if (rt.nextHopGateway == 0 || rt.nextHopGateway == -1) {
                    log.debug("icmp host unreachable, host mac unknown")
                    sendIcmpUnreachableHostError(inPort, pMatch, pFrame)
                } else {
                    log.debug("icmp net unreachable, gw mac unknown")
                    sendIcmpUnreachableNetError(inPort, pMatch, pFrame)
                }
                new ErrorDropAction: Action
            case nextHopMac =>
                log.debug("routing packet to {}", nextHopMac)
                pMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort): Action
        }

    }

    final protected def getRouterPort(portID: UUID, expiry: Long)
                                     (implicit actorSystem: ActorSystem,
                                      pktContext: PacketContext):
                                     Future[RouterPort[_]] = {
        expiringAsk(PortRequest(portID, update = false), expiry)
            .mapTo[RouterPort[_]] map {
            case null =>
                log.error("Can't find router port: {}", portID)
                null
            case rtrPort => rtrPort
        }
    }

    // Auxiliary, IP version specific abstract methods.

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipDest Final destination of the packet to be sent
     * @param expiry
     * @param ec
     * @return
     */
    protected def getNextHopMac(outPort: RouterPort[_], rt: Route,
                                         ipDest: IP, expiry: Long)
                                        (implicit ec: ExecutionContext,
                                         actorSystem: ActorSystem,
                                         pktContext: PacketContext): Future[MAC]

    /**
     * Will be called whenever an ICMP unreachable is needed for the given
     * IP version.
     */
    protected def sendIcmpUnreachableProhibError(inPort: RouterPort[_],
                                                          wMatch: WildcardMatch,
                                                          frame: Ethernet)
                                                (implicit ec: ExecutionContext,
                                                 actorSystem: ActorSystem,
                                                 originalPktContex: PacketContext)

    /**
     * Will be called whenever an ICMP Unreachable network is needed for the
     * given IP version.
     */
    protected def sendIcmpUnreachableNetError(inPort: RouterPort[_],
                                                       wMatch: WildcardMatch,
                                                       frame: Ethernet)
                                             (implicit ec: ExecutionContext,
                                              actorSystem: ActorSystem,
                                              originalPktContex: PacketContext)

    /**
     * Will be called whenever an ICMP Unreachable host is needed for the
     * given IP version.
     */
    protected def sendIcmpUnreachableHostError(inPort: RouterPort[_],
                                                        wMatch: WildcardMatch,
                                                        frame: Ethernet)
                                              (implicit ec: ExecutionContext,
                                               actorSystem: ActorSystem,
                                               originalPktContex: PacketContext)
    /**
     * Will be called whenever an ICMP Time Exceeded is needed for the given
     * IP version.
     */
    protected def sendIcmpTimeExceededError(inPort: RouterPort[_],
                                                     wMatch: WildcardMatch,
                                                     frame: Ethernet)
                                           (implicit ec: ExecutionContext,
                                            actorSystem: ActorSystem,
                                            originalPktContex: PacketContext)

    /**
     * Will be called to construct an ICMP echo reply for an ICMP echo reply
     * contained in the given packet.
     */
    protected def sendIcmpEchoReply(ingressMatch: WildcardMatch,
                                             packet: Ethernet, expiry: Long)
                                            (implicit ec: ExecutionContext,
                                             actorSystem: ActorSystem,
                                             packetContext: PacketContext)

    /**
     * Will be called from the pre-routing process immediately after receiving
     * the frame, if Ethernet.isBroadcast(hwDst).
     */
    protected def handleL2Broadcast(inPort: RouterPort[_])
                                   (implicit pktContext: PacketContext,
                                    ec: ExecutionContext,
                                    actorSystem: ActorSystem): Future[Action]

    /**
     * This method will be executed after basic L2 processing is done,
     * including handling broadcasts and reacting to frames not addressed to
     * our MAC.
     */
    protected def handleNeighbouring(inPort: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  pktContext: PacketContext,
                                  actorSystem: ActorSystem): Option[Action]

    protected def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean

}
