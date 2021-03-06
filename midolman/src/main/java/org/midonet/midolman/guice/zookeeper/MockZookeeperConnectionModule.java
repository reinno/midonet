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
package org.midonet.midolman.guice.zookeeper;

import javax.inject.Singleton;

import com.google.inject.name.Names;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.CallingThreadReactor;

public class MockZookeeperConnectionModule  extends ZookeeperConnectionModule {

    Directory directory;

    public MockZookeeperConnectionModule() {
        this(null);
    }

    public MockZookeeperConnectionModule(Directory directory) {
        this.directory = directory;
    }

    @Override
    protected void bindZookeeperConnection() {
        // no binding since we are mocking
    }

    @Override
    protected void bindDirectory() {
        if (directory == null) {
            bind(Directory.class)
                .to(MockDirectory.class)
                .in(Singleton.class);
        } else {
            bind(Directory.class)
                .toInstance(directory);
        }
    }

    @Override
    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
                Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG))
                .to(CallingThreadReactor.class)
                .asEagerSingleton();
    }
}
