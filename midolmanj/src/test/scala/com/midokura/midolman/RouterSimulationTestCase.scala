/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import compat.Platform
import scala.collection.mutable
import java.util.UUID
import akka.util.duration._
import akka.dispatch.Await
import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.Ignore
import guice.actors.OutgoingMessage


import com.midokura.midonet.cluster.data.{Router => ClusterRouter}
import com.midokura.packets._
import com.midokura.midolman.FlowController._
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
import simulation.{Router => SimRouter}
import layer3.Route.{NextHop, NO_GATEWAY}
import topology.VirtualToPhysicalMapper.{HostRequest, LocalPortActive}
import topology.VirtualTopologyActor.{PortRequest, RouterRequest}
import com.midokura.midonet.cluster.client.ExteriorRouterPort
import akka.util.Timeout
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.DatapathController.PacketIn

@RunWith(classOf[JUnitRunner])
class RouterSimulationTestCase extends MidolmanTestCase with
        VirtualConfigurationBuilders {
    private final val log = LoggerFactory.getLogger(classOf[RouterSimulationTestCase])

    private var flowEventsProbe: TestProbe = null

    private var router: ClusterRouter = null
    private val uplinkGatewayAddr = "180.0.1.1"
    private val uplinkNwAddr = 0x000000
    private val uplinkNwLen = 30
    private val uplinkPortAddr = "180.0.1.2"
    private val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")
    private var uplinkPort: MaterializedRouterPort = null
    private var upLinkRoute: UUID = null

    private val portConfigs = mutable.Map[Int, MaterializedRouterPort]()
    private val portNumToId = mutable.Map[Int, UUID]()
    private val portNumToMac = mutable.Map[Int, MAC]()
    private val portNumToName = mutable.Map[Int, String]()
    private val portNumToSegmentAddr = mutable.Map[Int, Int]()

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def before() {
        val host = newHost("myself", hostId())
        router = newRouter("router")
        // Create one port that works as an uplink for the router.
        uplinkPort = newPortOnRouter(router, uplinkMacAddr, uplinkPortAddr,
                                     uplinkPortAddr, uplinkNwLen,
                                     uplinkPortAddr, uplinkNwLen)

        initializeDatapath() should not be (null)

        materializePort(uplinkPort, host, "uplinkPort")
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())
        requestOfType[LocalPortActive](vtpProbe())

        upLinkRoute = newRoute(router, "0.0.0.0", 0, "45.0.0.0", 8, NextHop.PORT,
            uplinkPort.getId, uplinkGatewayAddr, 1)

        router should not be null
        uplinkPort should not be null
        upLinkRoute should not be null

        for (i <- 0 to 2) {
            // Nw address is 10.0.<i>.0/24
            val nwAddr = 0x0a000000 + (i << 8)
            // All ports in this subnet share the same ip address: 10.0.<i>.1
            val portAddr = nwAddr + 1
            for (j <- 1 to  3) {
                val macAddr = MAC.fromString("0a:0b:0c:0d:0" + i + ":0" + j)
                val portNum = i * 10 + j
                val portName = "port" + portNum
                // The port will route to 10.0.<i>.<j*4>/30
                val segmentAddr = new IntIPv4(nwAddr + (j * 4))

                val port = newPortOnRouter(router, macAddr,
                    new IntIPv4(portAddr).toString,
                    segmentAddr.toString, 30,
                    new IntIPv4(nwAddr).toString, 24)

                port should not be null

                materializePort(port, host, portName)
                requestOfType[OutgoingMessage](vtpProbe())
                requestOfType[LocalPortActive](vtpProbe())


                log.debug("Created router port {}, {}", portName, macAddr)

                // store port for later use
                portConfigs.put(portNum, port)
                portNumToId.put(portNum, port.getId)
                portNumToMac.put(portNum, macAddr)
                portNumToName.put(portNum, portName)
                portNumToSegmentAddr.put(portNum, segmentAddr.addressAsInt)

                // Default route to port based on destination only.  Weight 2.
                var rt = newRoute(router, "0.0.0.0", 0, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    2)
                /* XXX - discuss.
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt)
                }
                */

                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                rt = newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    1)
                // XXX see above.

                // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
                rt = newRoute(router, "11.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.BLACKHOLE, null, null, 1)

                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                rt = newRoute(router, "12.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.REJECT, null, null, 1)
                /* XXX - discuss
                if (1 != j) {
                    // Except for the first port, add them locally.
                    rtr.addPort(portId)
                }*/
            } // end for-loop on j
        } // end for-loop on i

        flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
    }

    private def feedArpCache(portName: String, srcIp: Int, srcMac: MAC,
                                               dstIp: Int, dstMac: MAC) {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(srcIp))
        arp.setTargetHardwareAddress(dstMac)
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(dstIp))

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(dstMac)
        eth.setEtherType(ARP.ETHERTYPE)
        triggerPacketIn(portName, eth)
    }

    private def expectPacketOnPort(portNum: Int): PacketIn = {
        dpProbe().expectMsgClass(classOf[PacketIn])

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be(portNumToId(portNum))
        pktInMsg
    }

    def testDropsIPv6() {
        val onPort = 12
        val IPv6_ETHERTYPE: Short = 0x86dd.toShort
        val eth = new Ethernet()
        eth.setDestinationMACAddress(portNumToMac(onPort))
        eth.setSourceMACAddress(MAC.fromString("01:02:03:04:05:06"))
        eth.setEtherType(IPv6_ETHERTYPE)
        eth.setPad(true)
        triggerPacketIn(portNumToName(onPort), eth)

        expectPacketOnPort(onPort)

        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        addFlowMsg.flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        addFlowMsg.flow.getMatch.getEtherType should equal(IPv6_ETHERTYPE)
        // A flow with no actions drops matching packets
        addFlowMsg.flow.getActions.size() should equal(0)
    }

    @Ignore def testForwardToUplink() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches the uplink
        // port (e.g. anything outside 10.  0.0.0/16).
        val onPort = 23
        val eth = Packets.udp(
            MAC.fromString("01:02:03:04:05:06"),
            portNumToMac(onPort),
            new IntIPv4((portNumToSegmentAddr(onPort) + 2)),
            IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(onPort)
        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            MAC.fromString("aa:bb:aa:cc:dd:cc"),
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)

        requestOfType[DiscardPacket](flowProbe())
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        val flow = addFlowMsg.flow
        flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        flow.getMatch.getEtherType should equal(IPv4.ETHERTYPE)
        flow.getMatch.getNetworkProtocol should equal(UDP.PROTOCOL_NUMBER)
        flow.getMatch.getTransportSource should equal(10)
        flow.getMatch.getTransportDestination should equal(11)
        // two actions: set hw.dst and emit through port
        flow.getActions.size() should not equal(0)
    }

    def testArpRequestFulfilledLocally() {
        feedArpCache("uplinkPort",
            IPv4.toIPv4Address(uplinkGatewayAddr),
            MAC.fromString("aa:bb:aa:cc:dd:cc"),
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)

        requestOfType[PortRequest](vtaProbe())
        requestOfType[OutgoingMessage](vtaProbe()) // ExteriorRouterPort
        requestOfType[PortRequest](vtaProbe())
        val port = requestOfType[OutgoingMessage](vtaProbe()).m.asInstanceOf[ExteriorRouterPort]

        requestOfType[RouterRequest](vtaProbe())
        val router = replyOfType[SimRouter](vtaProbe())
        requestOfType[PacketIn](simProbe())

        val expiry = Platform.currentTime + 1000
        val arpPromise = router.arpTable.get(IntIPv4.fromString(uplinkGatewayAddr),
            port, expiry)(actors().dispatcher, actors())
        val t = Timeout(3 seconds)
        val mac = Await.result(arpPromise, t.duration)
        mac should equal(MAC.fromString("aa:bb:aa:cc:dd:cc"))
    }

    /*
    @Ignore def testArpRequestNonLocalAddress() {
    }

    @Ignore def testArpRequestGeneration() {
    }

    @Ignore def testArpRequestRetry() {
    }

    @Ignore def testArpRequestTimeout() {
    }

    @Ignore def testArpRequestFulfilledLocally() {
    }

    @Ignore def testArpRequestFulfilledRemotely() {
    }

    @Ignore def testArpReceivedRequestProcessing() {
    }

    @Ignore def testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
    }

    @Ignore def testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
    }

    @Ignore def testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
    }

    @Ignore def testNoRoute() {
    }

    @Ignore def testICMPEcho() {
    }

    @Ignore def testUnlinkedLogicalPort() {
    }

    @Ignore def testFilterBadSrcForPort() {
    }

    @Ignore def testFilterBadDestination() {
    }

    @Ignore def testDnat() {
    }

    */
}