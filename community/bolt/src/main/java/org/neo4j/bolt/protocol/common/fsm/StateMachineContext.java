/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.protocol.common.fsm;

import java.time.Clock;
import org.neo4j.bolt.BoltChannel;
import org.neo4j.bolt.protocol.common.MutableConnectionState;
import org.neo4j.bolt.protocol.v41.message.request.RoutingContext;
import org.neo4j.bolt.runtime.BoltConnectionFatality;
import org.neo4j.bolt.transaction.TransactionManager;
import org.neo4j.internal.kernel.api.security.LoginContext;

public interface StateMachineContext {

    String connectionId();

    BoltChannel channel();

    Clock clock();

    TransactionManager transactionManager();

    StateMachineSPI boltSpi();

    MutableConnectionState connectionState();

    String defaultDatabase();

    void authenticatedAsUser(LoginContext loginContext, String userAgent);

    void impersonateUser(LoginContext loginContext);

    LoginContext getLoginContext();

    void handleFailure(Throwable cause, boolean fatal) throws BoltConnectionFatality;

    boolean resetMachine() throws BoltConnectionFatality;

    void initStatementProcessorProvider(RoutingContext routingContext);
}