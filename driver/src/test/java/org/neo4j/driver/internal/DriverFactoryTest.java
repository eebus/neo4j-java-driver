/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.internal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.neo4j.driver.internal.cluster.LoadBalancer;
import org.neo4j.driver.internal.cluster.RoutingSettings;
import org.neo4j.driver.internal.net.BoltServerAddress;
import org.neo4j.driver.internal.retry.RetryLogic;
import org.neo4j.driver.internal.retry.RetrySettings;
import org.neo4j.driver.internal.security.SecurityPlan;
import org.neo4j.driver.internal.spi.ConnectionPool;
import org.neo4j.driver.internal.spi.ConnectionProvider;
import org.neo4j.driver.v1.AuthToken;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.neo4j.driver.v1.AccessMode.READ;
import static org.neo4j.driver.v1.Config.defaultConfig;

@RunWith( Parameterized.class )
public class DriverFactoryTest
{
    @Parameter
    public URI uri;

    @Parameters( name = "{0}" )
    public static List<URI> uris()
    {
        return Arrays.asList(
                URI.create( "bolt://localhost:7687" ),
                URI.create( "bolt+routing://localhost:7687" )
        );
    }

    @Test
    public void connectionPoolClosedWhenDriverCreationFails() throws Exception
    {
        ConnectionPool connectionPool = mock( ConnectionPool.class );
        DriverFactory factory = new ThrowingDriverFactory( connectionPool );

        try
        {
            createDriver( factory );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( UnsupportedOperationException.class ) );
        }
        verify( connectionPool ).close();
    }

    @Test
    public void connectionPoolCloseExceptionIsSupressedWhenDriverCreationFails() throws Exception
    {
        ConnectionPool connectionPool = mock( ConnectionPool.class );
        RuntimeException poolCloseError = new RuntimeException( "Pool close error" );
        doThrow( poolCloseError ).when( connectionPool ).close();

        DriverFactory factory = new ThrowingDriverFactory( connectionPool );

        try
        {
            createDriver( factory );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( UnsupportedOperationException.class ) );
            assertArrayEquals( new Throwable[]{poolCloseError}, e.getSuppressed() );
        }
        verify( connectionPool ).close();
    }

    @Test
    public void usesStandardSessionFactoryWhenNothingConfigured()
    {
        Config config = Config.defaultConfig();
        SessionFactoryCapturingDriverFactory factory = new SessionFactoryCapturingDriverFactory();

        createDriver( factory, config );

        SessionFactory capturedFactory = factory.capturedSessionFactory;
        assertThat( capturedFactory.newInstance( READ, null ), instanceOf( NetworkSession.class ) );
    }

    @Test
    public void usesLeakLoggingSessionFactoryWhenConfigured()
    {
        Config config = Config.build().withLeakedSessionsLogging().toConfig();
        SessionFactoryCapturingDriverFactory factory = new SessionFactoryCapturingDriverFactory();

        createDriver( factory, config );

        SessionFactory capturedFactory = factory.capturedSessionFactory;
        assertThat( capturedFactory.newInstance( READ, null ), instanceOf( LeakLoggingNetworkSession.class ) );
    }

    private Driver createDriver( DriverFactory driverFactory )
    {
        return createDriver( driverFactory, defaultConfig() );
    }

    private Driver createDriver( DriverFactory driverFactory, Config config )
    {
        AuthToken auth = AuthTokens.none();
        RoutingSettings routingSettings = new RoutingSettings( 42, 42, null );
        return driverFactory.newInstance( uri, auth, routingSettings, RetrySettings.DEFAULT, config );
    }

    private static class ThrowingDriverFactory extends DriverFactory
    {
        final ConnectionPool connectionPool;

        ThrowingDriverFactory( ConnectionPool connectionPool )
        {
            this.connectionPool = connectionPool;
        }

        @Override
        protected InternalDriver createDriver( Config config, SecurityPlan securityPlan, SessionFactory sessionFactory )
        {
            throw new UnsupportedOperationException( "Can't create direct driver" );
        }

        @Override
        protected Driver createRoutingDriver( BoltServerAddress address, ConnectionPool connectionPool, Config config,
                RoutingSettings routingSettings, SecurityPlan securityPlan, RetryLogic retryLogic )
        {
            throw new UnsupportedOperationException( "Can't create routing driver" );
        }

        @Override
        protected ConnectionPool createConnectionPool( AuthToken authToken, SecurityPlan securityPlan, Config config )
        {
            return connectionPool;
        }
    }

    private static class SessionFactoryCapturingDriverFactory extends DriverFactory
    {
        SessionFactory capturedSessionFactory;

        @Override
        protected InternalDriver createDriver( Config config, SecurityPlan securityPlan, SessionFactory sessionFactory )
        {
            return null;
        }

        @Override
        protected LoadBalancer createLoadBalancer( BoltServerAddress address, ConnectionPool connectionPool,
                Config config, RoutingSettings routingSettings )
        {
            return null;
        }

        @Override
        protected SessionFactory createSessionFactory( ConnectionProvider connectionProvider,
                RetryLogic retryLogic, Config config )
        {
            SessionFactory sessionFactory = super.createSessionFactory( connectionProvider, retryLogic, config );
            capturedSessionFactory = sessionFactory;
            return sessionFactory;
        }
    }
}
