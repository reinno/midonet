package com.midokura.midolman;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory.*;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkPathManager;

public class Setup implements Watcher {

    static final Logger log = LoggerFactory.getLogger(Setup.class);
    private static final String ZK_CREATE = "zk_create";
    private static final String ZK_DESTROY = "zk_destory";
    private static final String ZK_SETUP = "zk_setup";
    private static final String ZK_TEARDOWN = "zk_teardown";
    private static final String OVS_SETUP = "zk_setup";
    private static final String OVS_TEARDOWN = "zk_teardown";

    private int disconnected_ttl_seconds;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> disconnected_kill_timer = null;
    private ZkConnection zkConnection;
    private Directory rootDir;
    private HierarchicalConfiguration config;
    private OpenvSwitchDatabaseConnection ovsdb;
    private String zkBasePath;
    BridgeZkManager bridgeMgr;
    ChainZkManager chainMgr;
    PortZkManager portMgr;
    RouterZkManager routerMgr;
    RouteZkManager routeMgr;
    RuleZkManager ruleMgr;

    private void run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        config = new HierarchicalINIConfiguration(configFilePath);
        executor = Executors.newScheduledThreadPool(1);

        args = cl.getArgs();
        if (args.length == 0)
            return;
        String command = args[0].toLowerCase();
        if (command.equals(ZK_CREATE))
            zkCreate();
        else if (command.equals(ZK_DESTROY))
            zkDestroy();
        else if (command.equals(ZK_SETUP))
            zkSetup();
        else if (command.equals(ZK_TEARDOWN))
            zkTearDown();
        else if (command.equals(OVS_SETUP))
            ovsSetup();
        else if (command.equals(OVS_TEARDOWN))
            ovsTearDown();
        else
            System.out.println("Unrecognized command. Exiting.");
    }

    private void zkCreate() throws Exception {
        initZK();
        String[] parts = zkBasePath.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.equals(""))
                continue;
            sb.append("/").append(p);
            zkBasePath = sb.toString();
            try {
                rootDir.add(zkBasePath, null, CreateMode.PERSISTENT);
            }
            catch(NodeExistsException e) {}
        }
        // Create all the top level directories
        Setup.createZkDirectoryStructure(rootDir, zkBasePath);
    }

    /**
     * Destroy the base path and everything underneath.
     * @throws Exception
     */
    private void zkDestroy() throws Exception {
        initZK();
        destroyZkDirectoryContents(zkBasePath);
        rootDir.delete(zkBasePath);
    }

    private void destroyZkDirectoryContents(String path) throws KeeperException,
            InterruptedException {
        Set<String> children = rootDir.getChildren(path, null);
        for (String child : children) {
            destroyZkDirectoryContents(path + "/" + child);
        }
        rootDir.delete(path);
    }

    private void zkSetup() throws Exception {
        initZK();

        // First, create a simple bridge with 3 ports.
        UUID deviceId = bridgeMgr.create(new BridgeConfig());
        System.out.println(String.format("Created a bridge with id %s",
                deviceId.toString()));
        UUID portId;
        PortConfig portConfig;
        for (int i = 0; i < 3; i++) {
            portConfig = new BridgePortConfig(deviceId);
            portId = portMgr.create(portConfig);
            System.out.println(String.format(
                    "Created a bridge port with id %s", portId.toString()));
        }
        // Now create a router with 3 ports.
        deviceId = routerMgr.create();
        System.out.println(String.format("Created a router with id %s",
                deviceId.toString()));
        // Add two ports to the router. Port-j should route to subnet
        // 10.0.<j>.0/24.
        int routerNw = 0x0a000000;
        for (int j = 0; j < 3; j++) {
            int portNw = routerNw + (j << 8);
            int portAddr = portNw + 1;
            portConfig = new PortDirectory.MaterializedRouterPortConfig(
                    deviceId, portNw, 24, portAddr, null, portNw, 24, null);
            portId = portMgr.create(portConfig);
            System.out.println(String.format("Created a router port with id "
                    + "%s that routes to %s", portId.toString(), IPv4
                    .addrToString(portNw)));
            Route rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 10,
                    null, deviceId);
            routeMgr.create(rt);
        }
    }

    /**
     * Remove everything from Midonet's top-level paths but leave those
     * directories.
     * @throws Exception
     */
    private void zkTearDown() throws Exception {
        initZK();
        Set<String> paths = getTopLevelPaths(zkBasePath);
        for (String path : paths) {
            destroyZkDirectoryContents(path);
        }
    }

    private void ovsSetup() {
        initOVS();
        /*
        String externalIdKey = config.configurationAt("openvswitch").getString(
                "midolman_ext_id_key", "midolman-vnet");
        String dpName = "mido_bridge1";
        BridgeBuilder ovsBridgeBuilder = ovsdb.addBridge(dpName);
        ovsBridgeBuilder.externalId(externalIdKey, deviceId.toString());
        ovsBridgeBuilder.build();
        PortBuilder ovsPortBuilder = ovsdb.addTapPort(dpName, "mido_br_port" + i);
        ovsPortBuilder.externalId(externalIdKey, portId.toString());
        ovsPortBuilder.build();
        */

    }

    private void ovsTearDown() {
        initOVS();
    }

    private void initZK() throws Exception {
        zkConnection = new ZkConnection(config.configurationAt("zookeeper")
                .getString("zookeeper_hosts", "127.0.0.1:2181"), config
                .configurationAt("zookeeper").getInt("session_timeout", 30000),
                this);

        log.debug("about to ZkConnection.open()");
        zkConnection.open();
        log.debug("done with ZkConnection.open()");

        rootDir = zkConnection.getRootDirectory();
        zkBasePath = config.configurationAt("midolman").getString(
                "midolman_root_key");

        bridgeMgr = new BridgeZkManager(rootDir, zkBasePath);
        chainMgr = new ChainZkManager(rootDir, zkBasePath);
        portMgr = new PortZkManager(rootDir, zkBasePath);
        routerMgr = new RouterZkManager(rootDir, zkBasePath);
        routeMgr = new RouteZkManager(rootDir, zkBasePath);
        ruleMgr = new RuleZkManager(rootDir, zkBasePath);
    }

    private void initOVS() {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch", config
                .configurationAt("openvswitch").getString(
                        "openvswitchdb_ip_addr", "127.0.0.1"), config
                .configurationAt("openvswitch").getInt(
                        "openvswitchdb_tcp_port", 6634));
    }

    @Override
    public synchronized void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");

            disconnected_kill_timer = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.error(
                            "have been disconnected for {} seconds, so exiting",
                            disconnected_ttl_seconds);
                    System.exit(-1);
                }
            }, disconnected_ttl_seconds, TimeUnit.SECONDS);
        }

        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            log.info("KeeperState is SyncConnected");

            if (disconnected_kill_timer != null) {
                log.info("canceling shutdown");
                disconnected_kill_timer.cancel(true);
                disconnected_kill_timer = null;
            }
        }

        if (event.getState() == Watcher.Event.KeeperState.Expired) {
            log.warn("KeeperState is Expired, shutdown now");
            System.exit(-1);
        }
    }

    private static Set<String> getTopLevelPaths(String basePath) {
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Set<String> paths = new HashSet<String>();
        paths.add(pathMgr.getBgpPath());
        paths.add(pathMgr.getBridgesPath());
        paths.add(pathMgr.getChainsPath());
        paths.add(pathMgr.getRulesPath());
        paths.add(pathMgr.getGrePath());
        paths.add(pathMgr.getPortsPath());
        paths.add(pathMgr.getRoutersPath());
        paths.add(pathMgr.getRoutesPath());
        paths.add(pathMgr.getVRNPortLocationsPath());
        return paths;
    }

    public static void createZkDirectoryStructure(Directory rootDir,
            String basePath) throws KeeperException, InterruptedException {
        Set<String> paths = Setup.getTopLevelPaths(basePath);
        for (String path : paths) {
            rootDir.add(path, null, CreateMode.PERSISTENT);
        }
    }

    public static void main(String[] args) {
        try {
            new Setup().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
