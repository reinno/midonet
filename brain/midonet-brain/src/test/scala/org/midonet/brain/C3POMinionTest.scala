/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.brain

import java.io.PrintWriter
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import scala.collection.JavaConverters._
import scala.util.{Random, Try}

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode.Context
import org.midonet.brain.services.c3po.{C3POConfig, C3POMinion}
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, NoData, Port => PortType, Router => RouterType, SecurityGroup => SecurityGroupType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.data.neutron.{NeutronResourceType, TaskType}
import org.midonet.cluster.data.storage.{ObjectReferencedException, Storage}
import org.midonet.cluster.models.{Commons, C3PO}
import org.midonet.cluster.models.Commons.{EtherType, Protocol, RuleDirection}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.storage.ZoomProvider
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.packets.{IPSubnet, IPv4Subnet, UDP}
import org.midonet.util.concurrent.toFutureOps

/** Tests the service that synces the Neutron DB into Midonet's backend. */
@RunWith(classOf[JUnitRunner])
class C3POMinionTest extends FlatSpec with BeforeAndAfter
                                      with BeforeAndAfterAll
                                      with Matchers {

    private val log = LoggerFactory.getLogger(this.getClass)

    private val ZK_PORT = 50000 + Random.nextInt(15000)
    private val ZK_HOST = s"127.0.0.1:$ZK_PORT"

    private val DB_CONNECT_STR =
        s"jdbc:sqlite:file:taskdb?mode=memory&cache=shared"

    private val DROP_TASK_TABLE = "DROP TABLE IF EXISTS midonet_tasks"
    private val EMPTY_TASK_TABLE = "DELETE FROM midonet_tasks"
    private val CREATE_TASK_TABLE =
        "CREATE TABLE midonet_tasks (" +
        "    id int(11) NOT NULL," +
        "    type_id int(11) NOT NULL," +
        "    data_type_id int(11) DEFAULT NULL," +
        "    data longtext," +
        "    resource_id varchar(36) DEFAULT NULL," +
        "    transaction_id varchar(40) NOT NULL," +
        "    created_at datetime NOT NULL," +
        "    PRIMARY KEY (id)" +
        ")"

    private val c3poCfg = new C3POConfig {
        override def periodMs: Long = 100
        override def delayMs: Long = 0
        override def isEnabled: Boolean = true
        override def minionClass: String = classOf[C3PO].getName
        override def numThreads: Int = 1
        override def connectionString: String = DB_CONNECT_STR
        override def user: String = ???
        override def password: String = ???
        override def jdbcDriver: String = ???
    }

    // Data sources
    private val zk: TestingServer = new TestingServer(ZK_PORT)

    private val nodeFactory = new JsonNodeFactory(true)

    // Adapt the DriverManager interface to DataSource interface.
    // SQLite doesn't seem to provide JDBC 2.0 API.
    private val dataSrc = new DataSource() {
        override def getConnection() = DriverManager.getConnection(DB_CONNECT_STR)
        override def getConnection(username: String, password: String) = null
        override def getLoginTimeout = -1
        override def getLogWriter = null
        override def setLoginTimeout(seconds: Int) {}
        override def setLogWriter(out: PrintWriter) {}
        override def getParentLogger = null
        override def isWrapperFor(clazz: Class[_]) = false
        override def unwrap[T](x: Class[T]): T = null.asInstanceOf[T]
    }

    // We need to keep one connection open to maintain the shared in-memory DB
    // during the test.
    private val dummyConnection = dataSrc.getConnection()

    // ---------------------
    // DATA FIXTURES
    // ---------------------

    private def executeSqlStmts(sqls: String*) {
        var c: Connection = null
        try {
            c = dataSrc.getConnection()
            val stmt = c.createStatement()
            sqls.foreach(stmt.executeUpdate)
            stmt.close()
        } finally {
            if (c != null) c.close()
        }
    }

    private def createTaskTable() = {
        // Just in case an old DB file / table exits.
        executeSqlStmts(DROP_TASK_TABLE)
        executeSqlStmts(CREATE_TASK_TABLE)
        log.info("Created the midonet_tasks table.")
    }

    def emptyTaskTableAndSendFlushTask() = {
        executeSqlStmts(EMPTY_TASK_TABLE)
        log.info("Emptied the task table.")

        // A flush task must have an id of 1 by spec.
        executeSqlStmts(insertMidoNetTaskSql(id = 1, Flush, NoData, json = "",
                                             null, "flush_txn"))
        log.info("Inserted a flush task.")
        Thread.sleep(1000)
    }

    private def insertMidoNetTaskSql(
            id: Int, taskType: TaskType, dataType: NeutronResourceType[_],
            json: String, resourceId: UUID, txnId: String) : String = {
        val rsrcIdStr = if (resourceId != null) s"'$resourceId'"
                        else "NULL"
        "INSERT INTO midonet_tasks values(" +
        s"$id, ${taskType.id}, ${dataType.id},'$json', $rsrcIdStr, '$txnId', " +
        "datetime('now'))"
    }

    val network1Uuid = UUID.fromString("d32019d3-bc6e-4319-9c1d-6722fc136a22")
    val network1Json =
        """{
            "status": "ACTIVE",
            "name": "private-network",
            "admin_state_up": true,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
            "router:external": true
        }"""
    val network1Json2 =
        """{
            "status": "ACTIVE",
            "name": "public-network",
            "admin_state_up": false,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
            "router:external": true
        }"""
    val network2Uuid = UUID.fromString("a305c946-fda6-4940-8ab1-fcf0d4d35dfd")
    val network2Json =
        """{
            "status": "ACTIVE",
            "name": "corporate-network",
            "admin_state_up": true,
            "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
            "shared": true,
            "id": "a305c946-fda6-4940-8ab1-fcf0d4d35dfd",
            "router:external": false
        }"""

    private var curator: CuratorFramework = _
    private var storage: Storage = _
    private var c3po: C3POMinion = _

    // ---------------------
    // TEST SETUP
    // ---------------------

    override protected def beforeAll() {
        try {
            val retryPolicy = new ExponentialBackoffRetry(1000, 10)
            curator = CuratorFrameworkFactory.newClient(ZK_HOST, retryPolicy)

            // Populate test data
            createTaskTable()

            zk.start()
            curator.start()
            curator.blockUntilConnected()

            storage = new ZoomProvider(curator).get()

            val nodeCtx = new Context(UUID.randomUUID())
            c3po = new C3POMinion(nodeCtx, c3poCfg, dataSrc, storage, curator)
            c3po.startAsync()
            c3po.awaitRunning(2, TimeUnit.SECONDS)
        } catch {
            case e: Throwable =>
                log.error("Failing setting up environment", e)
                cleanup()
        }
   }

    before {
        // Empties the task table and flush the topology before each test run.
        // NOTE: Closing the ZK Testing Server doesn't work because the Curator
        // client loses the connection. If we convert the test so that it runs
        // C3PO alone, we can instead just call C3PO.flushTopology() on C3PO.
        // Calling Storage.flush() doesn't do the job as it doesn't do necessary
        // initialization.
        emptyTaskTableAndSendFlushTask()
    }

    after {
        // curator.delete().deletingChildrenIfNeeded().forPath("/")
    }

    override protected def afterAll() {
        cleanup()
    }

    private def cleanup(): Unit = {
        Try(c3po.stopAsync()).getOrElse(log.error("Failed stopping c3po"))
        Try(curator.close()).getOrElse(log.error("Failed stopping curator"))
        Try(zk.stop()).getOrElse(log.error("Failed stopping zk"))
        Try(if (dummyConnection != null) dummyConnection.close())
            .getOrElse(log.error("Failed stopping the keep alive DB cnxn"))
    }

    private def portJson(name: String, id: UUID,
                         networkId: UUID,
                         adminStateUp: Boolean,
                         mac_address: String = null,
                         fixedIps: List[JsonNode] = null,
                         deviceId: UUID = null,
                         deviceOwner: DeviceOwner = null,
                         tenantId: String = null,
                         securityGroups: List[UUID] = null): JsonNode = {
        val sg = nodeFactory.objectNode
        sg.put("name", name)
        sg.put("id", id.toString)
        sg.put("network_id", networkId.toString)
        sg.put("admin_state_up", adminStateUp)
        sg.put("mac_address", mac_address)
        if (fixedIps != null) sg.putArray("fixed_ips").addAll(fixedIps.asJava)
        if (deviceId != null) sg.put("device_id", deviceId.toString)
        if (deviceOwner != null) sg.put("device_owner", deviceOwner.toString)
        if (tenantId != null) sg.put("tenant_id", tenantId)
        if (securityGroups != null) {
            val sgList = sg.putArray("security_groups")
            securityGroups.map(sgid => sgList.add(sgid.toString))
        }
        sg
    }

    private def sgJson(name: String, id: UUID,
                       desc: String = null,
                       tenantId: String = null,
                       rules: List[JsonNode]): JsonNode = {
        val sg = nodeFactory.objectNode
        sg.put("name", name)
        sg.put("id", id.toString)
        if (desc != null) sg.put("description", desc)
        if (tenantId != null) sg.put("tenant_id", tenantId)
        if (rules != null)
            sg.putArray("security_group_rules").addAll(rules.asJava)
        sg
    }

    private def ruleJson(id: UUID, sgId: UUID,
                         direction: RuleDirection = RuleDirection.INGRESS,
                         etherType: EtherType = EtherType.IPV4,
                         protocol: Protocol = Protocol.TCP,
                         portRange: Range = null,
                         remoteSgId: UUID = null,
                         remoteIpPrefix: IPSubnet[_] = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("id", id.toString)
        r.put("security_group_id", sgId.toString)
        r.put("direction", direction.toString)
        if (etherType != null)
            r.put("ethertype", etherType.toString)
        if (protocol != null)
            r.put("protocol", protocol.toString)
        if (portRange != null) {
            r.put("port_range_min", portRange.start)
            r.put("port_range_max", portRange.end)
        }
        if (remoteSgId != null)
            r.put("remote_group_id", remoteSgId.toString)
        if (remoteIpPrefix != null)
            r.put("remote_ip_prefix", remoteIpPrefix.toString)
        r
    }

    private def routerJson(name: String, id: UUID,
                           adminStateUp: Boolean = true,
                           status: String = null,
                           tenantId: String = null,
                           gwPortId: UUID = null,
                           enableSnat: Boolean = false,
                           extGwNetworkId: UUID = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("name", name)
        r.put("id", id.toString)
        r.put("admin_state_up", adminStateUp)
        if (status != null) r.put("status", status)
        if (tenantId != null) r.put("tenant_id", tenantId)
        if (gwPortId != null) r.put("gw_port_id", gwPortId.toString)
        if (enableSnat || extGwNetworkId != null) {
            val egi = nodeFactory.objectNode
            if (extGwNetworkId != null)
                egi.put("network_id", extGwNetworkId.toString)
            egi.put("enable_snat", enableSnat)
            r.put("extenal_gateway_info", egi)
        }
        r
    }


    "C3PO" should "poll DB and update ZK via C3POStorageMgr" in {
        val threadSleepMs = 2000
        // Initially the Storage is empty.
        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

        // Creates Network 1
        executeSqlStmts(insertMidoNetTaskSql(2, Create, NetworkType,
                                             network1Json, network1Uuid, "tx1"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe true
        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getId shouldBe toProto(network1Uuid)
        network1.getName shouldBe "private-network"
        network1.getAdminStateUp shouldBe true

        // Creates Network 2 and updates Network 1
        executeSqlStmts(
                insertMidoNetTaskSql(id = 3, Create, NetworkType, network2Json,
                                     network2Uuid, "tx2"),
                insertMidoNetTaskSql(id = 4, Update, NetworkType, network1Json2,
                                     network1Uuid, "tx2"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Network], network2Uuid).await() shouldBe true
        val network2 = storage.get(classOf[Network], network2Uuid).await()
        network2.getId shouldBe toProto(network2Uuid)
        network2.getName shouldBe "corporate-network"
        val network1a = storage.get(classOf[Network], network1Uuid).await()
        network1a.getId shouldBe toProto(network1Uuid)
        network1a.getName shouldBe "public-network"
        network1a.getAdminStateUp shouldBe false

        // Deletes Network 1
        executeSqlStmts(insertMidoNetTaskSql(
                id = 5, Delete, NetworkType, json = "", network1Uuid, "tx3"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

        // Empties the Task table and flushes the Topology.
        emptyTaskTableAndSendFlushTask()

        storage.exists(classOf[Network], network2Uuid).await() shouldBe false

        // Can create Network 1 & 2 again.
        executeSqlStmts(
                insertMidoNetTaskSql(id = 2, Create, NetworkType, network1Json,
                                     network1Uuid, "tx4"),
                insertMidoNetTaskSql(id = 3, Create, NetworkType, network2Json,
                                     network2Uuid, "tx4"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Network], network1Uuid).await() shouldBe true
        storage.exists(classOf[Network], network2Uuid).await() shouldBe true
    }

    it should "manage port binding to a Network" in {
        val threadSleepMs = 1000
        // Creates Network 1.
        executeSqlStmts(insertMidoNetTaskSql(
                id = 2, Create, NetworkType, network1Json, network1Uuid, "tx1"))
        Thread.sleep(threadSleepMs)

        val vifPortUuid = UUID.randomUUID()
        val vifPortId = toProto(vifPortUuid)
        storage.exists(classOf[Port], vifPortId).await() shouldBe false

        // Creates a VIF port.
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid,
                                   adminStateUp = true).toString
        executeSqlStmts(insertMidoNetTaskSql(
                id = 3, Create, PortType, vifPortJson, vifPortUuid, "tx2"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Port], vifPortId).await() shouldBe true
        val vifPort = storage.get(classOf[Port], vifPortId).await()
        vifPort.getId should be (vifPortId)
        vifPort.getNetworkId should be (toProto(network1Uuid))
        vifPort.getAdminStateUp shouldBe true

        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getPortIdsList should contain (vifPortId)

        // Update the port admin status. Through the Neutron API, you cannot
        // change the Network the port is attached to.
        val vifPortUpdate = portJson(name = "port1", id = vifPortUuid,
                                     networkId = network1Uuid,
                                     adminStateUp = false      // Down now.
                                     ).toString
        executeSqlStmts(insertMidoNetTaskSql(
                id = 4, Update, PortType, vifPortUpdate, vifPortUuid, "tx3"))
        Thread.sleep(threadSleepMs)

        val updatedVifPort = storage.get(classOf[Port], vifPortId).await()
        updatedVifPort.getAdminStateUp shouldBe false
        // Deleting a network while ports are attached should throw exception.
        intercept[ObjectReferencedException] {
            storage.delete(classOf[Network], network1Uuid)
        }

        // Delete the VIF port.
        executeSqlStmts(insertMidoNetTaskSql(
                id = 5, Delete, PortType, json = null, vifPortUuid, "tx4"))
        Thread.sleep(threadSleepMs)

        storage.exists(classOf[Port], vifPortId).await() shouldBe false
        // Back reference was cleared.
        val finalNw1 = storage.get(classOf[Network], network1Uuid).await()
        finalNw1.getPortIdsList should not contain (vifPortId)
        // You can delete the Network1 now.
        storage.delete(classOf[Network], network1Uuid)
    }

    it should "handle security group CRUD" in {
        val sg1Id = UUID.randomUUID()
        val rule1Id = UUID.randomUUID()
        val rule1Json = ruleJson(
            rule1Id, sg1Id, portRange = 15000 to 15500,
            remoteIpPrefix = new IPv4Subnet("10.0.0.1", 24))

        val sg2Id = UUID.randomUUID()
        val rule2Id = UUID.randomUUID()
        val rule2Json = ruleJson(rule2Id, sg1Id, etherType = EtherType.IPV6,
                                 remoteSgId = sg2Id)

        val sg1Json = sgJson(name = "sg1", id = sg1Id,
                             desc = "Security group", tenantId = "tenant",
                             rules = List(rule1Json, rule2Json))
        val sg2Json = sgJson(name ="sg2", id = sg2Id,
                             tenantId = "tenant", rules = List())
        executeSqlStmts(insertMidoNetTaskSql(2, Create, SecurityGroupType,
                                             sg1Json.toString, sg1Id, "tx1"),
                        insertMidoNetTaskSql(3, Create, SecurityGroupType,
                                             sg2Json.toString, sg2Id, "tx1"))
        Thread.sleep(1000)

        val ipg1 = storage.get(classOf[IpAddrGroup], sg1Id).await()
        val ChainPair(inChain1, outChain1) = getChains(ipg1)

        inChain1.getRuleIdsCount should be(0)
        outChain1.getRuleIdsCount should be(2)
        val outChain1Rules = storage.getAll(classOf[Rule],
                                            outChain1.getRuleIdsList.asScala)
                                    .map(_.await())
        outChain1Rules(0).getId should be(toProto(rule1Id))
        outChain1Rules(0).getTpDst.getStart should be(15000)
        outChain1Rules(0).getTpDst.getEnd should be(15500)
        outChain1Rules(0).getNwSrcIp.getAddress should be("10.0.0.1")
        outChain1Rules(0).getNwSrcIp.getPrefixLength should be(24)

        outChain1Rules(1).getId should be(toProto(rule2Id))
        outChain1Rules(1).getDlType should be(EtherType.IPV6_VALUE)
        outChain1Rules(1).getIpAddrGroupIdSrc should be(toProto(sg2Id))

        val rule1aJson = ruleJson(rule1Id, sg1Id,
                                  direction = RuleDirection.EGRESS,
                                  protocol = Protocol.UDP)
        val rule3Id = UUID.randomUUID()
        val rule3Json = ruleJson(rule3Id, sg1Id)
        val sg1aJson = sgJson(name = "sg1-updated", id = sg1Id,
                              desc = "Security group", tenantId = "tenant",
                              rules = List(rule1aJson, rule3Json))
        executeSqlStmts(insertMidoNetTaskSql(4, Update, SecurityGroupType,
                                             sg1aJson.toString,
                                             sg1Id, "tx2"),
                        insertMidoNetTaskSql(5, Delete, SecurityGroupType,
                                             null, sg2Id, "tx2"))
        Thread.sleep(1000)

        val ipg1a = storage.get(classOf[IpAddrGroup], sg1Id).await()
        val ChainPair(inChain1a, outChain1a) = getChains(ipg1a)

        inChain1a.getId should be(inChain1.getId)
        inChain1a.getRuleIdsCount should be(1)
        inChain1a.getRuleIds(0) should be(toProto(rule1Id))

        outChain1a.getId should be(outChain1.getId)
        outChain1a.getRuleIdsCount should be(1)
        outChain1a.getRuleIds(0) should be(toProto(rule3Id))

        val ipg1aRules = storage.getAll(classOf[Rule],
                                        List(rule1Id, rule3Id)).map(_.await())

        val inChain1aRule1 = ipg1aRules(0)
        inChain1aRule1.getId should be(toProto(rule1Id))
        inChain1aRule1.getNwProto should be(UDP.PROTOCOL_NUMBER)

        val outChain1aRule1 = ipg1aRules(1)
        outChain1aRule1.getId should be(toProto(rule3Id))

        executeSqlStmts(insertMidoNetTaskSql(6, Delete, SecurityGroupType,
                                             null, sg1Id, "tx3"))
        Thread.sleep(1000)

        val delFutures = List(storage.getAll(classOf[SecurityGroup]),
                              storage.getAll(classOf[IpAddrGroup]),
                              storage.getAll(classOf[Chain]),
                              storage.getAll(classOf[Rule]))
        val delResults = delFutures.map(_.await())
        delResults.foreach(r => r should be(empty))
    }

    it should "handle router CRUD" in {
        val r1Id = UUID.randomUUID()
        val r1Json = routerJson("router1", r1Id)
        executeSqlStmts(insertMidoNetTaskSql(2, Create, RouterType,
                                             r1Json.toString, r1Id, "tx1"))
        Thread.sleep(1000)

        val r1 = storage.get(classOf[Router], r1Id).await()
        UUIDUtil.fromProto(r1.getId) shouldBe r1Id
        r1.getName shouldBe "router1"
        r1.getAdminStateUp shouldBe true
        r1.getInboundFilterId should not be null
        r1.getOutboundFilterId should not be null

        val r1Chains = getChains(r1.getInboundFilterId, r1.getOutboundFilterId)
        r1Chains.inChain.getRuleIdsCount shouldBe 0
        r1Chains.outChain.getRuleIdsCount shouldBe 0

        val r2Id = UUID.randomUUID()
        val r2Json = routerJson("router2", r2Id, adminStateUp = false)
        val r1JsonV2 = routerJson("router1", r1Id, tenantId = "new-tenant")
        executeSqlStmts(insertMidoNetTaskSql(3, Create, RouterType,
                                             r2Json.toString, r2Id, "tx2"),
                        insertMidoNetTaskSql(4, Update, RouterType,
                                             r1JsonV2.toString, r1Id, "tx2"))
        Thread.sleep(1000)

        val tx2Futures = storage.getAll(classOf[Router], List(r1Id, r2Id))
        val List(r1V2, r2) = tx2Futures.map(_.await())
        r1V2.getTenantId shouldBe "new-tenant"
        r2.getName shouldBe "router2"

        executeSqlStmts(
            insertMidoNetTaskSql(5, Delete, RouterType, null, r1Id, "tx3"),
            insertMidoNetTaskSql(6, Delete, RouterType, null, r2Id, "tx3"))
        Thread.sleep(1000)

        val tx3Futures = storage.getAll(classOf[Router]).await()
        tx3Futures.size shouldBe 0
    }

    case class ChainPair(inChain: Chain, outChain: Chain)
    private def getChains(inChainId: Commons.UUID,
                          outChainId: Commons.UUID): ChainPair = {
        val fs = storage.getAll(classOf[Chain], List(inChainId, outChainId))
        val chains = fs.map(_.await())
        ChainPair(chains(0), chains(1))
    }

    private def getChains(ipg: IpAddrGroup): ChainPair =
        getChains(ipg.getInboundChainId, ipg.getOutboundChainId)

}
