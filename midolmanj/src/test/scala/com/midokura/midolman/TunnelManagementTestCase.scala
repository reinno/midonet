/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import java.util.UUID
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.slf4j.{Logger, LoggerFactory}

import com.midokura.midolman.DatapathController.DatapathPortChangedEvent
import com.midokura.midolman.topology.physical
import com.midokura.midolman.topology.VirtualToPhysicalMapper._
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.zones.{GreTunnelZone,
                                                GreTunnelZoneHost}
import com.midokura.packets.IntIPv4
import com.midokura.sdn.dp.ports.{NetDevPort, GreTunnelPort}


@RunWith(classOf[JUnitRunner])
class TunnelManagementTestCase extends MidolmanTestCase with ShouldMatchers {

    private final val log: Logger = LoggerFactory.getLogger(classOf[TunnelManagementTestCase])

    val myselfId = UUID.randomUUID()

    override protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
        config.setProperty("host-host_uuid", myselfId.toString)
        config
    }

    import scala.collection.JavaConversions._

    def testTunnelZone() {

        // make the default gre tunnel zone
        val greZone = new GreTunnelZone().setName("greDefault")
        clusterDataClient().tunnelZonesCreate(greZone)

        // make a bridge
        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        // make a port on the bridge
        val inputPort = Ports.materializedBridgePort(bridge)
        inputPort.setId(clusterDataClient().portsCreate(inputPort))

        // make a host for myself and put in the proper tunnel zone.
        val me =
            new Host(hostId())
                .setName("myself").setTunnelZones(Set(greZone.getId))
        val myGreConfig = new GreTunnelZoneHost(me.getId)
            .setIp(IntIPv4.fromString("192.168.100.1"))
        clusterDataClient().hostsCreate(me.getId, me)
        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, myGreConfig)

        // make another host and put in the same tunnel zone.
        val she = new Host(UUID.randomUUID())
            .setName("herself")
            .setTunnelZones(Set(greZone.getId))

        val herGreConfig = new GreTunnelZoneHost(she.getId)
            .setIp(IntIPv4.fromString("192.168.200.1"))
        clusterDataClient().hostsCreate(she.getId, she)
        clusterDataClient()
            .tunnelZonesAddMembership(greZone.getId, herGreConfig)

        // make the bridge port to a local interface
        clusterDataClient().hostsAddVrnPortMapping(hostId, inputPort.getId, "port1")

        // make a probe and make it listen to the DatapathPortChangedEvents (fired by the Datapath Controller)
        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[DatapathPortChangedEvent])

        // start initialization
        initializeDatapath() should not be (null)

        // assert that the port event was fired properly
        var portChangedEvent = eventProbe.expectMsgClass(classOf[DatapathPortChangedEvent])
        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("port1")
        portChangedEvent.port.isInstanceOf[NetDevPort] should be(true)

        // assert that the VTP got a HostRequest message
        requestOfType[HostRequest](vtpProbe())
        replyOfType[physical.Host](vtpProbe())

        // assert that the VTP got a TunnelZoneRequest message for the proper zone
        val tzRequest = requestOfType[TunnelZoneRequest](vtpProbe())
        tzRequest.zoneId should be === greZone.getId
        replyOfType[GreTunnelZone](vtpProbe())
        replyOfType[GreZoneChanged](vtpProbe())
        replyOfType[GreZoneChanged](vtpProbe())

        // assert that the creation event for the tunnel was fired.
        portChangedEvent = requestOfType[DatapathPortChangedEvent](eventProbe)
        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("tngreC0A8C801")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        var grePort = portChangedEvent.port.asInstanceOf[GreTunnelPort]
        grePort.getOptions.getSourceIPv4 should be(myGreConfig.getIp.addressAsInt())
        grePort.getOptions.getDestinationIPv4 should be(herGreConfig.getIp.addressAsInt())

        // check the internal data in the datapath controller is correct
        // the host peer contains a map which maps the zone to the tunnel name
        dpController().underlyingActor.peerPorts should contain key (she.getId)
        dpController().underlyingActor.peerPorts should contain value (
            scala.collection.mutable.Map(greZone.getId -> "tngreC0A8C801")
            )

        // update the gre ip of the second host
        val herSecondGreConfig = new GreTunnelZoneHost(she.getId)
            .setIp(IntIPv4.fromString("192.168.210.1"))
        clusterDataClient().tunnelZonesAddMembership(
            greZone.getId, herSecondGreConfig)

        // assert a delete event was fired on the bus.
        portChangedEvent = requestOfType[DatapathPortChangedEvent](eventProbe)
        portChangedEvent.op should be(PortOperation.Delete)
        portChangedEvent.port.getName should be("tngreC0A8C801")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        // assert the proper dapath port changed event is fired
        portChangedEvent = requestOfType[DatapathPortChangedEvent](eventProbe)

        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.getName should be("tngreC0A8D201")
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        grePort = portChangedEvent.port.asInstanceOf[GreTunnelPort]

        grePort.getOptions.getSourceIPv4 should be(myGreConfig.getIp.addressAsInt())
        grePort.getOptions.getDestinationIPv4 should be(herSecondGreConfig.getIp.addressAsInt())

        // assert the internal state of the datapath controller vas fired
        dpController().underlyingActor.peerPorts should contain key (she.getId)
        dpController().underlyingActor.peerPorts should contain value (
            scala.collection.mutable.Map(greZone.getId -> "tngreC0A8D201")
        )

        val dp = dpConn().datapathsGet("midonet").get()
        dp should not be (null)

        val ports = datapathPorts(dp)
        ports should have size 3
        ports should contain key ("midonet")
        ports should contain key ("port1")
        ports should contain key ("tngreC0A8D201")
    }
}
