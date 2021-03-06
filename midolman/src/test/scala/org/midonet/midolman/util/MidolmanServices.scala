/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.util

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import com.google.inject.Injector

import org.midonet.cluster.Client
import org.midonet.cluster.DataClient
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.flows.FlowEjector
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.util.mock.{MockFlowEjector, MockDatapathChannel, MockUpcallDatapathConnectionManager}
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}

trait MidolmanServices {
    var injector: Injector

    def clusterClient =
        injector.getInstance(classOf[Client])

    implicit def clusterDataClient =
        injector.getInstance(classOf[DataClient])

    implicit def hostId =
        injector.getInstance(classOf[HostIdProviderService]).getHostId

    def mockDpConn()(implicit ec: ExecutionContext, as: ActorSystem) = {
        dpConn().asInstanceOf[MockOvsDatapathConnection]
    }

    def mockDpChannel = {
        injector.getInstance(classOf[DatapathChannel])
                .asInstanceOf[MockDatapathChannel]
    }

    def mockFlowEjector = {
        injector.getInstance(classOf[FlowEjector])
                .asInstanceOf[MockFlowEjector]
    }

    def dpConn()(implicit ec: ExecutionContext, as: ActorSystem):
        OvsDatapathConnection = {
        val mockConnManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        mockConnManager.initialize()
        mockConnManager.conn.getConnection
    }
}
